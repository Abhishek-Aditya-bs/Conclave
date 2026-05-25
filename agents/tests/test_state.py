"""Tests for the DeliberationState + Decision dataclasses."""
from __future__ import annotations

import operator
from typing import get_type_hints

import pytest

from deliberation.state import (
    BaselineFinding,
    ContributingFactor,
    Decision,
    DeliberationState,
    FeatureSummary,
    GraphFinding,
    VerdictLabel,
)


class TestVerdictLabel:
    @pytest.mark.parametrize(
        ("score", "expected"),
        [
            (0.0, VerdictLabel.ALLOW),
            (0.29, VerdictLabel.ALLOW),
            (0.30, VerdictLabel.REVIEW),
            (0.50, VerdictLabel.REVIEW),
            (0.69, VerdictLabel.REVIEW),
            (0.70, VerdictLabel.BLOCK),
            (1.00, VerdictLabel.BLOCK),
        ],
    )
    def test_from_score(self, score: float, expected: VerdictLabel) -> None:
        assert VerdictLabel.from_score(score) == expected

    def test_value_is_uppercase(self) -> None:
        assert VerdictLabel.ALLOW.value == "ALLOW"
        assert VerdictLabel.REVIEW.value == "REVIEW"
        assert VerdictLabel.BLOCK.value == "BLOCK"


class TestDecision:
    def test_valid_decision_round_trip(self, sample_decision: Decision) -> None:
        # Frozen + slots: no mutation possible.
        with pytest.raises(AttributeError):
            sample_decision.score = 0.5  # type: ignore[misc]

    def test_score_out_of_range_rejected(self) -> None:
        with pytest.raises(ValueError, match=r"score must be in \[0, 1\]"):
            Decision(
                score=1.5,
                verdict_label=VerdictLabel.BLOCK,
                verdict_explanation_md="x",
                contributing_factors=(),
            )

    def test_empty_explanation_rejected(self) -> None:
        with pytest.raises(ValueError, match="explanation_md must be non-empty"):
            Decision(
                score=0.5,
                verdict_label=VerdictLabel.REVIEW,
                verdict_explanation_md="   ",
                contributing_factors=(),
            )


class TestContributingFactor:
    def test_constructable(self) -> None:
        cf = ContributingFactor(name="x", weight=0.3, evidence="because")
        assert cf.name == "x"
        assert cf.weight == 0.3


class TestFeatureSummary:
    def test_defaults_numeric_to_empty_dict(self) -> None:
        fs = FeatureSummary(event_id="e", domain="fraud", headline="h", bullets=())
        assert fs.numeric == {}


class TestBaselineFinding:
    def test_cold_start_defaults(self) -> None:
        b = BaselineFinding(
            entity_id="e", domain="fraud", is_cold_start=True,
            event_count=0, embedding_dim=0,
        )
        assert b.cosine_similarity is None
        assert b.note == ""


class TestGraphFinding:
    def test_attributes_dict(self) -> None:
        g = GraphFinding(
            template_name="t", root_entity_id="r", domain="fraud",
            attributes={"k": 1}, risk_signal=0.5, query_latency_ms=3,
        )
        assert g.attributes == {"k": 1}


class TestDeliberationState:
    def test_errors_uses_concat_reducer(self) -> None:
        # The Annotated[..., operator.add] reducer is what lets parallel
        # nodes append to `errors` without clobbering each other.
        hints = get_type_hints(DeliberationState, include_extras=True)
        errors_hint = hints["errors"]
        # operator.add should be in the annotation metadata.
        assert errors_hint.__metadata__ == (operator.add,)

    def test_state_keys_present(self) -> None:
        # Sanity check: spec-aligned keys.
        hints = get_type_hints(DeliberationState, include_extras=True)
        expected = {
            "event_id", "domain", "baseline_entity_id", "graph_entity_ids",
            "enriched_event_json",
            "feature_summary", "baseline_finding", "graph_finding",
            "decision", "errors",
        }
        assert expected.issubset(hints.keys())
