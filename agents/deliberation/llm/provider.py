"""LLM provider factory + shared types.

Two backends:

* ``anthropic`` — default; uses the Anthropic Python SDK against
  ``claude-haiku-4-5-20251001`` (spec rule #6). Structured output is
  produced by forcing tool use against a single ``record_decision`` tool
  whose schema mirrors the ``Decision`` proto.
* ``ollama`` — opt-in; uses ``langchain-ollama``'s ``ChatOllama`` with
  ``format="json"`` against a local Ollama server. The same JSON schema
  is embedded in the prompt and validated post-hoc.

The factory is keyed off the ``JUDGE_LLM_PROVIDER`` env var. Published
benchmarks must come from the Anthropic backend (spec §5 caveat); the
Ollama path exists for self-hosters who can't or won't use the Anthropic
API.
"""
from __future__ import annotations

import json
import logging
import os
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any

from deliberation.state import (
    BaselineFinding,
    ContributingFactor,
    Decision,
    FeatureSummary,
    GraphFinding,
    VerdictLabel,
)

_LOG = logging.getLogger(__name__)

# Spec rule #6 — judge model lock. Do not silently swap to Sonnet/Opus.
DEFAULT_ANTHROPIC_MODEL = "claude-haiku-4-5-20251001"
# Local default — the Gemma the project owner has pulled in their Ollama.
DEFAULT_OLLAMA_MODEL = "gemma4:e4b"
# Serving default for the OpenAI-compatible path.
DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"

_PROVIDER_ANTHROPIC = "anthropic"
_PROVIDER_OLLAMA = "ollama"
_PROVIDER_OPENAI = "openai"
_VALID_PROVIDERS = (_PROVIDER_ANTHROPIC, _PROVIDER_OLLAMA, _PROVIDER_OPENAI)


# -------------------- I/O contract --------------------

@dataclass(frozen=True, slots=True)
class JudgeInput:
    """The evidence packet the judge node hands to the LLM."""

    feature_summary: FeatureSummary
    baseline_finding: BaselineFinding | None
    graph_finding: GraphFinding | None


@dataclass(frozen=True, slots=True)
class JudgeOutput:
    """Provider-agnostic shape that the judge node converts to ``Decision``."""

    score: float
    verdict_label: VerdictLabel
    verdict_explanation_md: str
    contributing_factors: tuple[ContributingFactor, ...]
    raw_response: str = ""  # for debugging / replay


class LLMProviderError(RuntimeError):
    """Raised when a provider can't produce a parseable decision.

    The judge node catches this and falls back to a no-LLM Decision
    derived directly from the structured findings — see ``nodes/judge.py``.
    """


# -------------------- Provider ABC --------------------

class LLMProvider(ABC):
    """Strategy interface for the judge LLM."""

    name: str  # "anthropic" | "ollama"
    model: str

    @abstractmethod
    def produce_decision(self, judge_input: JudgeInput, *, domain: str) -> JudgeOutput:
        """Single-shot call. Raises ``LLMProviderError`` on parse failure."""


# -------------------- Prompts --------------------

# System prompt is held constant for prompt-caching (Anthropic side).
JUDGE_SYSTEM_PROMPT = """\
You are the JUDGE agent in CONCLAVE, a multi-agent risk-detection platform. \
Three earlier agents (feature extractor, behavioral baseliner, graph reasoner) \
have assembled an evidence package for a single event. Your job is to produce \
a single Decision JSON object.

The Decision MUST include:
  * score             — calibrated risk in [0, 1]
  * verdict_label     — one of "ALLOW" (<0.30), "REVIEW" (0.30-0.70), "BLOCK" (≥0.70)
  * verdict_explanation_md — 2-4 sentence Markdown explanation a fraud reviewer or
                             SOC analyst can act on. Cite specific evidence; avoid
                             generic language.
  * contributing_factors — ordered list (most influential first) of {name, weight, evidence}
                           where weight is signed in [-1, 1]; positive nudges BLOCK,
                           negative nudges ALLOW.

Calibration guidance:
  * Cold-start entities (no baseline yet) get a mild prior of risk, not a verdict.
  * Graph risk_signal ≥ 0.8 with a named structural pattern (ring, lateral) is strong.
  * High velocity alone is suggestive but not dispositive — combine with other signals.
  * Be honest about uncertainty. The verdict drives downstream automation; over-blocking
    has a real cost.

Output ONLY the structured Decision via the tool / JSON contract provided. \
Do not add prose outside that structure.
"""

# Stable factor vocabulary — the prompt hints at these so contributing_factors
# names stabilize across runs (helps the audit UI group decisions).
SUGGESTED_FACTOR_VOCABULARY = (
    "behavioral_anomaly",
    "behavioral_cold_start",
    "graph_ring_detected",
    "graph_lateral_movement",
    "graph_privileged_access",
    "high_velocity",
    "elevated_bin_risk",
    "failed_login_burst",
    "geographic_mismatch",
    "device_novelty",
    "no_anomaly_observed",
)


def build_user_prompt(judge_input: JudgeInput, *, domain: str) -> str:
    """Render the evidence package into a single user-turn prompt.

    Deterministic format so prompt-cache hits are maximized on the
    Anthropic side and post-hoc replay is reproducible.
    """
    parts: list[str] = []
    parts.append(f"## Event {judge_input.feature_summary.event_id} (domain: {domain})")
    parts.append("")
    parts.append("### Feature summary")
    parts.append(judge_input.feature_summary.headline)
    for bullet in judge_input.feature_summary.bullets:
        parts.append(f"- {bullet}")
    if judge_input.feature_summary.numeric:
        parts.append("")
        parts.append("Numeric features:")
        for key, value in sorted(judge_input.feature_summary.numeric.items()):
            parts.append(f"- {key} = {value}")

    parts.append("")
    parts.append("### Behavioral baseline (M3)")
    if judge_input.baseline_finding is None:
        parts.append("_M3 unavailable — proceed with no behavioral signal._")
    elif judge_input.baseline_finding.is_cold_start:
        parts.append(
            f"Cold-start entity '{judge_input.baseline_finding.entity_id}' — "
            f"no baseline history yet (0 prior events)."
        )
    else:
        b = judge_input.baseline_finding
        parts.append(
            f"Entity '{b.entity_id}' has {b.event_count} prior events; "
            f"baseline embedding dim={b.embedding_dim}."
        )
        if b.cosine_similarity is not None:
            parts.append(
                f"Cosine similarity of current event to rolling baseline: "
                f"{b.cosine_similarity:.3f}."
            )
        if b.note:
            parts.append(f"Note: {b.note}")

    parts.append("")
    parts.append("### Graph reasoning (M4)")
    if judge_input.graph_finding is None:
        parts.append("_M4 unavailable or no template applied._")
    else:
        g = judge_input.graph_finding
        parts.append(
            f"Template '{g.template_name}' rooted at '{g.root_entity_id}' "
            f"reports risk_signal={g.risk_signal:.2f} (latency={g.query_latency_ms}ms)."
        )
        if g.attributes:
            parts.append("Attributes:")
            parts.append("```json")
            parts.append(json.dumps(g.attributes, indent=2, sort_keys=True))
            parts.append("```")
        if g.note:
            parts.append(f"Note: {g.note}")

    parts.append("")
    parts.append(
        "Suggested factor vocabulary (use names from this list when applicable): "
        + ", ".join(SUGGESTED_FACTOR_VOCABULARY)
    )
    parts.append("")
    parts.append("Produce the Decision now.")
    return "\n".join(parts)


# -------------------- Decision schema (shared) --------------------

# The tool schema (Anthropic) and the JSON schema (Ollama) are the same shape
# — only the binding differs.
DECISION_JSON_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "score": {
            "type": "number",
            "minimum": 0.0,
            "maximum": 1.0,
            "description": "Calibrated risk score.",
        },
        "verdict_label": {
            "type": "string",
            "enum": ["ALLOW", "REVIEW", "BLOCK"],
        },
        "verdict_explanation_md": {
            "type": "string",
            "description": "2-4 sentence Markdown explanation citing specific evidence.",
        },
        "contributing_factors": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "weight": {"type": "number", "minimum": -1.0, "maximum": 1.0},
                    "evidence": {"type": "string"},
                },
                "required": ["name", "weight", "evidence"],
                "additionalProperties": False,
            },
            "minItems": 1,
        },
    },
    "required": ["score", "verdict_label", "verdict_explanation_md", "contributing_factors"],
    "additionalProperties": False,
}


# -------------------- Parsing --------------------

def parse_decision_payload(payload: dict[str, Any], *, raw_response: str = "") -> JudgeOutput:
    """Validate + coerce a JSON payload into a ``JudgeOutput``.

    Raises ``LLMProviderError`` on any structural problem so the judge
    node can fall back deterministically.
    """
    try:
        score = float(payload["score"])
    except (KeyError, TypeError, ValueError) as exc:
        raise LLMProviderError(f"score missing or not numeric: {payload}") from exc
    if not 0.0 <= score <= 1.0:
        raise LLMProviderError(f"score out of [0, 1]: {score}")

    label_str = payload.get("verdict_label")
    if not isinstance(label_str, str):
        raise LLMProviderError(f"verdict_label missing or not str: {payload}")
    try:
        verdict_label = VerdictLabel(label_str.upper())
    except ValueError as exc:
        raise LLMProviderError(f"verdict_label not in vocabulary: {label_str}") from exc

    explanation = payload.get("verdict_explanation_md")
    if not isinstance(explanation, str) or not explanation.strip():
        raise LLMProviderError(f"verdict_explanation_md missing or empty: {payload}")

    raw_factors = payload.get("contributing_factors", [])
    if not isinstance(raw_factors, list) or not raw_factors:
        raise LLMProviderError(f"contributing_factors must be non-empty list: {payload}")

    factors: list[ContributingFactor] = []
    for f in raw_factors:
        if not isinstance(f, dict):
            raise LLMProviderError(f"contributing factor is not an object: {f}")
        try:
            factors.append(
                ContributingFactor(
                    name=str(f["name"]),
                    weight=float(f["weight"]),
                    evidence=str(f["evidence"]),
                )
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise LLMProviderError(f"contributing factor malformed: {f}") from exc

    return JudgeOutput(
        score=score,
        verdict_label=verdict_label,
        verdict_explanation_md=explanation,
        contributing_factors=tuple(factors),
        raw_response=raw_response,
    )


def derive_fallback_decision(judge_input: JudgeInput, *, domain: str) -> JudgeOutput:
    """Heuristic fallback when the LLM can't produce a valid Decision.

    Combines the structured signals deterministically so the orchestrator
    never returns no-decision. The fallback's job is to be safe (lean
    toward REVIEW on ambiguity) and explainable, not to be smart.
    """
    factors: list[ContributingFactor] = []
    score = 0.10  # baseline prior

    if judge_input.graph_finding is not None:
        g = judge_input.graph_finding
        if g.risk_signal > 0.0:
            score = max(score, g.risk_signal)
            factors.append(
                ContributingFactor(
                    name="graph_signal",
                    weight=g.risk_signal,
                    evidence=(
                        f"M4 template '{g.template_name}' returned "
                        f"risk_signal={g.risk_signal:.2f}."
                    ),
                )
            )

    if judge_input.baseline_finding is not None and judge_input.baseline_finding.is_cold_start:
        score = max(score, 0.35)
        factors.append(
            ContributingFactor(
                name="behavioral_cold_start",
                weight=0.25,
                evidence=(
                    f"Entity '{judge_input.baseline_finding.entity_id}' has no "
                    f"behavioral baseline (cold-start)."
                ),
            )
        )

    if not factors:
        factors.append(
            ContributingFactor(
                name="no_anomaly_observed",
                weight=0.0,
                evidence="Fallback path: no LLM verdict available; no strong signals.",
            )
        )

    return JudgeOutput(
        score=score,
        verdict_label=VerdictLabel.from_score(score),
        verdict_explanation_md=(
            "**Fallback verdict.** The judge LLM did not return a parseable "
            "structured decision; this verdict was derived deterministically "
            "from the structured findings. Treat as low-confidence."
        ),
        contributing_factors=tuple(factors),
        raw_response="<fallback>",
    )


# -------------------- Decision ↔ JudgeOutput helpers --------------------

def to_decision(
    output: JudgeOutput,
    *,
    latency_ms: int,
    judge_provider: str,
    judge_model: str,
) -> Decision:
    """Stamp provider metadata + latency onto a ``JudgeOutput``."""
    return Decision(
        score=output.score,
        verdict_label=output.verdict_label,
        verdict_explanation_md=output.verdict_explanation_md,
        contributing_factors=output.contributing_factors,
        latency_ms=latency_ms,
        judge_provider=judge_provider,
        judge_model=judge_model,
    )


# -------------------- Factory --------------------

def build_provider_from_env(env: dict[str, str] | None = None) -> LLMProvider:
    """Construct an ``LLMProvider`` from environment variables.

    Recognized vars (with defaults):
      * ``JUDGE_LLM_PROVIDER`` — ``anthropic`` (default) | ``openai`` | ``ollama``
      * ``JUDGE_LLM_MODEL``    — provider-specific default if unset
      * ``ANTHROPIC_API_KEY``  — required when provider=anthropic
      * ``OPENAI_API_KEY``     — required when provider=openai
      * ``OPENAI_BASE_URL``    — https://api.openai.com/v1 by default; point at
                                 Ollama Cloud / OpenRouter / Groq / ... here
      * ``OLLAMA_BASE_URL``    — http://localhost:11434 by default

    The two run modes wire these for you:
      * ``local``   → provider=ollama against the host's Ollama (Gemma).
      * ``serving`` → provider=anthropic (default) or openai, using a cloud key.

    Importing the provider modules is lazy so a missing optional dep
    (e.g. langchain-ollama not installed) doesn't break the Anthropic
    path.
    """
    env_map = env if env is not None else os.environ
    provider_name = env_map.get("JUDGE_LLM_PROVIDER", _PROVIDER_ANTHROPIC).lower()
    if provider_name not in _VALID_PROVIDERS:
        raise ValueError(
            f"JUDGE_LLM_PROVIDER='{provider_name}' invalid; "
            f"expected one of {_VALID_PROVIDERS}"
        )

    # Docker Compose passes unset variables through as the empty string, so we
    # treat "" / whitespace as "not provided" and fall back to provider defaults.
    model_override = (env_map.get("JUDGE_LLM_MODEL") or "").strip()

    if provider_name == _PROVIDER_ANTHROPIC:
        model = model_override or DEFAULT_ANTHROPIC_MODEL
        api_key = env_map.get("ANTHROPIC_API_KEY")
        if not api_key:
            raise ValueError(
                "ANTHROPIC_API_KEY is required when JUDGE_LLM_PROVIDER=anthropic. "
                "Set it, or switch to JUDGE_LLM_PROVIDER=ollama for local mode."
            )
        from deliberation.llm.anthropic_provider import AnthropicProvider

        _LOG.info("Judge provider = anthropic; model = %s", model)
        return AnthropicProvider(model=model, api_key=api_key)

    if provider_name == _PROVIDER_OPENAI:
        model = model_override or DEFAULT_OPENAI_MODEL
        api_key = env_map.get("OPENAI_API_KEY")
        if not api_key:
            raise ValueError(
                "OPENAI_API_KEY is required when JUDGE_LLM_PROVIDER=openai. "
                "It also authenticates Ollama Cloud / OpenRouter / Groq / etc. "
                "via OPENAI_BASE_URL."
            )
        base_url = (env_map.get("OPENAI_BASE_URL") or "").strip() or DEFAULT_OPENAI_BASE_URL
        from deliberation.llm.openai_provider import OpenAIProvider

        _LOG.info(
            "Judge provider = openai-compatible; model = %s; base_url = %s", model, base_url
        )
        return OpenAIProvider(model=model, api_key=api_key, base_url=base_url)

    # provider_name == _PROVIDER_OLLAMA
    model = model_override or DEFAULT_OLLAMA_MODEL
    base_url = (env_map.get("OLLAMA_BASE_URL") or "").strip() or "http://localhost:11434"
    from deliberation.llm.ollama_provider import OllamaProvider

    _LOG.info("Judge provider = ollama; model = %s; base_url = %s", model, base_url)
    return OllamaProvider(model=model, base_url=base_url)
