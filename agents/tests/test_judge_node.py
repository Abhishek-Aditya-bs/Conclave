"""Judge-node tests — provider mocked, fallback path exercised."""
from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from deliberation.llm import LLMProviderError
from deliberation.llm.provider import JudgeOutput
from deliberation.nodes.judge import make_judge_node
from deliberation.state import (
    BaselineFinding,
    ContributingFactor,
    FeatureSummary,
    GraphFinding,
    VerdictLabel,
)


def _state(**overrides):
    base = {
        "event_id": "e",
        "domain": "fraud",
        "baseline_entity_id": "c",
        "graph_entity_ids": [],
        "enriched_event_json": "{}",
        "feature_summary": FeatureSummary(
            event_id="e", domain="fraud", headline="h", bullets=("b",), numeric={"x": 1.0},
        ),
        "baseline_finding": BaselineFinding(
            entity_id="c", domain="fraud", is_cold_start=False, event_count=10, embedding_dim=384,
        ),
        "graph_finding": GraphFinding(
            template_name="t", root_entity_id="c", domain="fraud",
            attributes={}, risk_signal=0.3, query_latency_ms=2,
        ),
    }
    base.update(overrides)
    return base


class TestJudgeNode:
    def test_happy_path_uses_provider_output(self):
        provider = MagicMock()
        provider.name = "anthropic"
        provider.model = "claude-haiku-4-5-20251001"
        provider.produce_decision.return_value = JudgeOutput(
            score=0.66,
            verdict_label=VerdictLabel.REVIEW,
            verdict_explanation_md="Mixed signals.",
            contributing_factors=(
                ContributingFactor(name="high_velocity", weight=0.3, evidence="v=17"),
            ),
        )
        node = make_judge_node(provider)
        result = node(_state())

        decision = result["decision"]
        assert decision.score == 0.66
        assert decision.verdict_label == VerdictLabel.REVIEW
        assert decision.judge_provider == "anthropic"
        assert decision.judge_model == "claude-haiku-4-5-20251001"
        assert decision.latency_ms >= 1
        assert "errors" not in result

    def test_provider_error_falls_back(self):
        provider = MagicMock()
        provider.name = "ollama"
        provider.model = "qwen3:8b"
        provider.produce_decision.side_effect = LLMProviderError("bad json")

        node = make_judge_node(provider)
        result = node(_state())

        decision = result["decision"]
        # Fallback explanation flagged.
        assert "Fallback" in decision.verdict_explanation_md
        assert decision.judge_provider == "ollama"
        assert "errors" in result
        assert "fallback" in result["errors"][0]

    def test_missing_feature_summary_raises(self):
        provider = MagicMock()
        node = make_judge_node(provider)
        state = _state()
        state.pop("feature_summary")
        with pytest.raises(RuntimeError, match="before feature_node"):
            node(state)
