"""LLM provider factory + shared types.

Two backends:

* ``anthropic`` — default; uses the Anthropic Python SDK against
  ``claude-haiku-4-5-20251001``. Structured output is
  produced by forcing tool use against a single ``record_decision`` tool
  whose schema mirrors the ``Decision`` proto.
* ``ollama`` — opt-in; uses ``langchain-ollama``'s ``ChatOllama`` with
  ``format="json"`` against a local Ollama server. The same JSON schema
  is embedded in the prompt and validated post-hoc.

The factory is keyed off the ``JUDGE_LLM_PROVIDER`` env var. Published
benchmarks must come from the Anthropic backend; the
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

# Judge model lock. Do not silently swap to Sonnet/Opus.
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

The three signals have very different reliability. Weigh them by what each can
actually see:
  * EXPLICIT RULE FEATURES (failed-login bursts, velocity, privilege, result,
    amount, merchant) come straight from the event and its recent history. They
    are DIRECT, high-precision evidence of what actually happened. A severe rule
    indicator is strong evidence of attack ON ITS OWN.
  * GRAPH risk_signal with a NAMED pattern (card_testing_ring, lateral_movement) is
    a CONFIRMED structural campaign, not a guess.
  * The BEHAVIORAL baseline (cosine similarity / anomaly_score) is the WEAKEST and
    most easily fooled signal. It only measures whether this event resembles the
    SAME entity's own recent events. It does NOT understand result=FAILURE,
    automation user-agents, privilege, or amount magnitude. When an attacker
    repeats a malicious action many times (e.g. a failed-login burst), the entity's
    rolling profile DRIFTS toward the attack, so cosine stays HIGH (anomaly LOW)
    even mid-attack. Categorical and novel attacks routinely embed as "normal".

Calibration guidance (do NOT change the numeric bands):
  * Anchor the score on the STRONGEST piece of DIRECT evidence (a severe rule
    indicator or a confirmed graph pattern), NOT on the behavioral signal.
  * A confirmed graph pattern (named: card_testing_ring / lateral_movement) with
    risk_signal ≥ 0.8 is decisive → score ≥ 0.80, BLOCK.
  * A single SEVERE rule indicator is enough for high-REVIEW or BLOCK on its own:
      - failed_logins_recent ≥ 10 (failed-login / credential-stuffing burst),
        especially with result=FAILURE and an automation/scripted user-agent
        (curl, wget, python, boto3, bot) → score ≥ 0.75, BLOCK.
      - repeated FAILURE from an automation user-agent → attack-grade.
      - privileged access from an unusual / automation context → attack-grade.
      - card-testing velocity (one device, ≥3 distinct cards, small-amount
        FAILUREs) → attack-grade (BLOCK if the graph also names card_testing_ring).
      - bust-out / account-takeover spending: a large amount AND a new merchant
        (out-of-character high-value charge) → score ≥ 0.70, BLOCK; a large amount
        OR a new merchant alone → REVIEW.
  * A "normal" behavioral signal (high cosine / low anomaly) is WEAK corroboration,
    NOT exoneration. It MUST NOT cancel, override, or heavily discount a severe
    rule indicator or a confirmed graph pattern. If the rules or graph say attack
    but the embedding says normal, TRUST the rules/graph — the embedding is blind
    to this attack class. Only lower a score for a normal embedding when the
    rule/graph evidence is itself weak or absent.
  * A high behavioral anomaly (low cosine) WITH no corroborating rule or graph
    signal is at most REVIEW, not BLOCK (embeddings also false-positive).
  * Cold-start entities (no baseline yet) get a mild prior of risk, not a verdict.
  * KEEP DISCIPLINE on clean traffic: with NO severe rule indicator, NO named graph
    pattern, and a normal embedding (e.g. a routine result=SUCCESS browser login,
    low velocity, no failed logins; or a normal-amount grocery purchase), the score
    must stay in ALLOW. Do not invent risk — only genuine attack indicators escalate.
    Over-blocking benign traffic has a real cost.
  * Be decisive: corroborating signals push toward the extremes. Do not cluster
    everything at 0.5.

CALIBRATION EXAMPLES (note how DIRECT evidence overrules a normal embedding):
  A. Card-testing ring → BLOCK. graph risk_signal=0.91 pattern=card_testing_ring;
     behavioral cosine=0.41 anomaly=0.88 → score≈0.90, BLOCK (named pattern decisive).
  B. Normal browser login → ALLOW. failed_logins_recent=0, velocity=2,
     result=SUCCESS, browser agent, not privileged; cosine=0.97 anomaly=0.03
     → score≈0.06, ALLOW.
  C. Failed-login burst, embedding looks NORMAL → BLOCK. failed_logins_recent=15,
     result=FAILURE, curl/automation agent; cosine=0.98 anomaly=0.02 → score≈0.82,
     BLOCK. The high cosine only means the attacker repeated the same action so the
     profile drifted; it is NOT exoneration and must not discount the burst.
  D. Bust-out spending → BLOCK. amount large AND a brand-new merchant,
     result=SUCCESS; cosine=0.62 anomaly=0.38 → score≈0.74, BLOCK.
  E. A few failed logins from a browser, mild anomaly, no named pattern → REVIEW
     (≈0.50): ambiguous, needs a human.

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
    parts.append("### Behavioral baseline")
    if judge_input.baseline_finding is None:
        parts.append("_Baseline unavailable — proceed with no behavioral signal._")
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
        if b.anomaly_score is not None:
            parts.append(
                f"Behavioral anomaly score (0=matches history, 1=deviant): "
                f"{b.anomaly_score:.3f}."
            )
        if b.cosine_similarity is not None and b.cosine_similarity >= 0.90:
            parts.append(
                "Note: this is the WEAKEST signal. A high cosine only means this event "
                "resembles the SAME entity's recent events; for a repeated attack the "
                "profile drifts toward the attack, so a normal embedding here is NOT "
                "exoneration and must not cancel a severe rule indicator or named "
                "graph pattern."
            )
        if b.note:
            parts.append(f"Note: {b.note}")

    parts.append("")
    parts.append("### Graph reasoning")
    if judge_input.graph_finding is None:
        parts.append("_Graph reasoning unavailable or no template applied._")
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

    # Deterministic restatement of the severe attack flags already present in the
    # feature/graph evidence above (no new data, no verdict) so the judge does not
    # overlook a strong indicator buried in the numeric dump.
    indicators = _direct_attack_indicators(judge_input)
    parts.append("")
    parts.append("### Direct attack indicators (factual flags from rules/graph)")
    if indicators:
        for ind in indicators:
            parts.append(f"- {ind}")
    else:
        parts.append("- none")

    parts.append("")
    parts.append(
        "Suggested factor vocabulary (use names from this list when applicable): "
        + ", ".join(SUGGESTED_FACTOR_VOCABULARY)
    )
    parts.append("")
    parts.append(
        "Produce the Decision now. Anchor the score on the strongest direct "
        "indicator or confirmed graph pattern; a normal embedding does not cancel them."
    )
    return "\n".join(parts)


def _direct_attack_indicators(judge_input: JudgeInput) -> list[str]:
    """Surface factual, rubric-aligned attack flags from the rule/graph signals.

    Pure restatement of fields already in the digest — no new evidence and no
    verdict. The numeric feature keys mirror ``nodes/feature.py``:
    ``failed_logins_recent``, ``principal_velocity``, ``is_privileged`` (security)
    and ``amount_minor``, ``cardholder_velocity``, ``bin_risk_score`` (fraud).
    """
    out: list[str] = []
    fs = judge_input.feature_summary
    numeric = fs.numeric or {}

    def _num(key: str) -> float:
        try:
            return float(numeric.get(key, 0.0) or 0.0)
        except (TypeError, ValueError):
            return 0.0

    # The headline/bullets carry the categorical fields (result, agent, merchant);
    # scan them case-insensitively for the discriminating tokens.
    blob = " ".join([fs.headline, *fs.bullets]).lower()
    is_failure = "result=failure" in blob
    is_automation = any(
        tok in blob for tok in ("curl", "wget", "python", "boto3", "go-http", "bot", "scan")
    )

    # --- security indicators ---
    failed = _num("failed_logins_recent")
    if failed >= 10:
        suffix = ""
        if is_automation:
            suffix += " from an automation/scripted user-agent"
        if is_failure:
            suffix += " with result=FAILURE"
        out.append(
            f"SEVERE: failed-login burst (failed_logins_recent={int(failed)}){suffix}"
        )
    elif failed >= 5:
        out.append(f"failed-login cluster (failed_logins_recent={int(failed)})")

    if is_automation and is_failure:
        out.append("repeated FAILURE from an automation/scripted agent")

    if _num("is_privileged") >= 1.0 and is_automation:
        out.append("privileged access from an automation/unusual context")

    # --- fraud indicators ---
    amount_minor = _num("amount_minor")
    new_merchant = "newmerchant" in blob or "new merchant" in blob or "new-merchant" in blob
    if amount_minor >= 100_000 and new_merchant:
        out.append(
            f"bust-out spending: large amount={amount_minor / 100:.2f} to a NEW merchant"
        )
    elif amount_minor >= 100_000:
        out.append(f"large amount={amount_minor / 100:.2f} (single-signal)")
    elif new_merchant:
        out.append("charge to a new merchant (single-signal)")

    # --- graph pattern ---
    g = judge_input.graph_finding
    if g is not None and g.risk_signal >= 0.80:
        cardholders = g.attributes.get("cardholderCount") if g.attributes else None
        hosts = g.attributes.get("hostCount") if g.attributes else None
        detail = ""
        if cardholders:
            detail = f" ({cardholders} cardholders share one device)"
        elif hosts:
            detail = f" ({hosts} distinct hosts)"
        out.append(
            f"confirmed graph pattern '{g.template_name}' at risk={g.risk_signal:.2f}{detail}"
        )

    return out


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
                        f"Graph template '{g.template_name}' returned "
                        f"risk_signal={g.risk_signal:.2f}."
                    ),
                )
            )

    b = judge_input.baseline_finding
    if b is not None and b.is_cold_start:
        score = max(score, 0.35)
        factors.append(
            ContributingFactor(
                name="behavioral_cold_start",
                weight=0.25,
                evidence=(
                    f"Entity '{b.entity_id}' has no behavioral baseline (cold-start)."
                ),
            )
        )
    elif b is not None and b.anomaly_score is not None:
        # Cosine-based behavioral deviation drives the no-LLM fallback too, so the
        # similarity signal still shapes the decision when the judge is unavailable.
        score = max(score, b.anomaly_score)
        cosine_note = (
            f", cosine={b.cosine_similarity:.2f}"
            if b.cosine_similarity is not None
            else ""
        )
        factors.append(
            ContributingFactor(
                name="behavioral_anomaly",
                weight=b.anomaly_score,
                evidence=(
                    f"Event deviates from entity '{b.entity_id}' baseline: "
                    f"anomaly={b.anomaly_score:.2f}{cosine_note}."
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
