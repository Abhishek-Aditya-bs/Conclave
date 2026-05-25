"""LangGraph state shape + Decision dataclass.

The state is a ``TypedDict`` (LangGraph's required form) with one key per
node's output, plus an ``errors`` list reduced with concatenation so any
node that catches an exception can append context without clobbering the
sibling branches that succeeded.

The ``Decision`` dataclass mirrors the proto message in
``deliberation.proto`` field-for-field; see spec §6 M5 for the contract.
"""
from __future__ import annotations

import operator
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Annotated, Any, TypedDict

# -------------------- Verdict labels --------------------

class VerdictLabel(StrEnum):
    """Bucketed risk verdict.

    Threshold mapping is spec §6 M5 + ADR-004:
      * score < 0.30 → ALLOW
      * 0.30 ≤ score < 0.70 → REVIEW
      * score ≥ 0.70 → BLOCK

    The judge LLM is asked to emit a label directly; we use these constants
    as the canonical vocabulary in prompts and as a fallback derivation when
    the LLM emits a score but no label.
    """

    ALLOW = "ALLOW"
    REVIEW = "REVIEW"
    BLOCK = "BLOCK"

    @classmethod
    def from_score(cls, score: float) -> VerdictLabel:
        if score >= 0.70:
            return cls.BLOCK
        if score >= 0.30:
            return cls.REVIEW
        return cls.ALLOW


# -------------------- Decision payloads --------------------

@dataclass(frozen=True, slots=True)
class ContributingFactor:
    """One driver behind the judge's verdict.

    ``weight`` is signed in ``[-1, 1]``: positive nudges toward BLOCK,
    negative nudges toward ALLOW, zero is informational. ``evidence`` is a
    single sentence the audit UI can show without parsing the explanation
    markdown.
    """

    name: str
    weight: float
    evidence: str


@dataclass(frozen=True, slots=True)
class Decision:
    """Final output of the deliberation graph.

    Field-for-field mirror of the proto ``Decision`` message; the gRPC
    server is responsible for translating between this Python form and the
    wire form.
    """

    score: float
    verdict_label: VerdictLabel
    verdict_explanation_md: str
    contributing_factors: tuple[ContributingFactor, ...]
    latency_ms: int = 0
    judge_provider: str = ""
    judge_model: str = ""

    def __post_init__(self) -> None:
        if not 0.0 <= self.score <= 1.0:
            raise ValueError(f"score must be in [0, 1]; got {self.score}")
        if not self.verdict_explanation_md.strip():
            raise ValueError("verdict_explanation_md must be non-empty")


# -------------------- Per-node finding shapes --------------------

@dataclass(frozen=True, slots=True)
class FeatureSummary:
    """Output of the feature node — a human-readable digest of the event.

    The judge prompt embeds this verbatim. We keep it structured rather
    than pure-text so unit tests can assert on individual fields.
    """

    event_id: str
    domain: str
    headline: str
    bullets: tuple[str, ...]
    # Numeric features carried through for the judge's quantitative reasoning.
    numeric: dict[str, float] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class BaselineFinding:
    """Output of the behavioral baseliner node.

    A baseline does NOT exist for a brand-new entity; ``is_cold_start`` is
    True in that case and ``embedding`` / ``event_count`` are zero/empty.
    The judge treats cold-start as a mild risk signal (no track record).
    """

    entity_id: str
    domain: str
    is_cold_start: bool
    event_count: int
    embedding_dim: int  # 384 for the all-MiniLM-L6-v2 backing
    last_updated_epoch_ms: int = 0
    # Optional similarity score against a freshly-encoded event-text vector.
    # Populated by the baseliner node when it has both vectors; the judge
    # reads it as "how much does this event look like the entity's history".
    cosine_similarity: float | None = None
    # Free-form note the baseliner can emit (e.g. "M3 unreachable, degraded").
    note: str = ""


@dataclass(frozen=True, slots=True)
class GraphFinding:
    """Output of the graph reasoner node.

    Mirrors the proto ``GraphFinding`` 1:1 plus ``template_name`` so the
    judge prompt can reference the template by name in its explanation.
    """

    template_name: str
    root_entity_id: str
    domain: str
    attributes: dict[str, Any]  # parsed from the proto JSON string
    risk_signal: float  # [0, 1] hint
    query_latency_ms: int
    note: str = ""


# -------------------- LangGraph state --------------------

class DeliberationState(TypedDict, total=False):
    """The state passed between LangGraph nodes.

    ``total=False`` because each node only writes the keys it owns; the
    runtime merges. ``errors`` uses operator.add as a reducer so multiple
    branches can append without colliding.
    """

    # Inputs (set by the server before invoking the graph) --------------
    event_id: str
    domain: str
    baseline_entity_id: str
    graph_entity_ids: list[str]
    enriched_event_json: str

    # Per-node outputs (each owned by exactly one node) -----------------
    feature_summary: FeatureSummary
    baseline_finding: BaselineFinding | None
    graph_finding: GraphFinding | None
    decision: Decision

    # Reducer-merged log of partial failures across branches ------------
    errors: Annotated[list[str], operator.add]
