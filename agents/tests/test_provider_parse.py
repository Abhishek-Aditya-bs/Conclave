"""Shared parser + fallback derivation logic in llm.provider."""
from __future__ import annotations

import pytest

from deliberation.llm import LLMProviderError
from deliberation.llm.provider import (
    JudgeInput,
    build_user_prompt,
    derive_fallback_decision,
    parse_decision_payload,
    to_decision,
)
from deliberation.state import VerdictLabel


class TestParseDecisionPayload:
    def _ok(self, **overrides):
        base = {
            "score": 0.5,
            "verdict_label": "REVIEW",
            "verdict_explanation_md": "Mixed evidence.",
            "contributing_factors": [
                {"name": "x", "weight": 0.4, "evidence": "y"}
            ],
        }
        base.update(overrides)
        return base

    def test_happy_path(self):
        out = parse_decision_payload(self._ok())
        assert out.verdict_label == VerdictLabel.REVIEW
        assert out.contributing_factors[0].weight == 0.4

    def test_label_is_normalized_uppercase(self):
        out = parse_decision_payload(self._ok(verdict_label="block"))
        assert out.verdict_label == VerdictLabel.BLOCK

    def test_score_out_of_range(self):
        with pytest.raises(LLMProviderError, match="out of"):
            parse_decision_payload(self._ok(score=1.7))

    def test_score_missing(self):
        bad = self._ok()
        bad.pop("score")
        with pytest.raises(LLMProviderError, match="score missing"):
            parse_decision_payload(bad)

    def test_score_not_numeric(self):
        with pytest.raises(LLMProviderError, match="not numeric"):
            parse_decision_payload(self._ok(score="high"))

    def test_label_unknown(self):
        with pytest.raises(LLMProviderError, match="not in vocabulary"):
            parse_decision_payload(self._ok(verdict_label="DEFER"))

    def test_label_missing(self):
        bad = self._ok()
        bad.pop("verdict_label")
        with pytest.raises(LLMProviderError, match="missing or not str"):
            parse_decision_payload(bad)

    def test_explanation_empty(self):
        with pytest.raises(LLMProviderError, match="explanation_md"):
            parse_decision_payload(self._ok(verdict_explanation_md="   "))

    def test_explanation_missing(self):
        bad = self._ok()
        bad.pop("verdict_explanation_md")
        with pytest.raises(LLMProviderError, match="explanation_md"):
            parse_decision_payload(bad)

    def test_contributing_factors_empty(self):
        with pytest.raises(LLMProviderError, match="non-empty list"):
            parse_decision_payload(self._ok(contributing_factors=[]))

    def test_contributing_factors_missing(self):
        bad = self._ok()
        bad.pop("contributing_factors")
        with pytest.raises(LLMProviderError, match="non-empty list"):
            parse_decision_payload(bad)

    def test_contributing_factor_not_object(self):
        with pytest.raises(LLMProviderError, match="not an object"):
            parse_decision_payload(self._ok(contributing_factors=["bad"]))

    def test_contributing_factor_missing_field(self):
        with pytest.raises(LLMProviderError, match="malformed"):
            parse_decision_payload(
                self._ok(contributing_factors=[{"name": "x", "weight": 0.2}])  # no evidence
            )


class TestDeriveFallbackDecision:
    def test_no_signals_low_score(self, sample_feature_summary):
        out = derive_fallback_decision(
            JudgeInput(
                feature_summary=sample_feature_summary,
                baseline_finding=None,
                graph_finding=None,
            ),
            domain="fraud",
        )
        assert out.score == 0.10
        assert out.verdict_label == VerdictLabel.ALLOW
        assert out.contributing_factors[0].name == "no_anomaly_observed"

    def test_graph_risk_lifts_score(self, sample_feature_summary, sample_graph_finding):
        out = derive_fallback_decision(
            JudgeInput(
                feature_summary=sample_feature_summary,
                baseline_finding=None,
                graph_finding=sample_graph_finding,
            ),
            domain="fraud",
        )
        assert out.score == pytest.approx(0.82)
        assert out.verdict_label == VerdictLabel.BLOCK
        names = {f.name for f in out.contributing_factors}
        assert "graph_signal" in names

    def test_cold_start_lifts_to_review(self, sample_feature_summary):
        from deliberation.state import BaselineFinding

        cold = BaselineFinding(
            entity_id="new",
            domain="fraud",
            is_cold_start=True,
            event_count=0,
            embedding_dim=0,
        )
        out = derive_fallback_decision(
            JudgeInput(
                feature_summary=sample_feature_summary,
                baseline_finding=cold,
                graph_finding=None,
            ),
            domain="fraud",
        )
        assert out.score >= 0.30
        assert out.verdict_label == VerdictLabel.REVIEW
        assert any(f.name == "behavioral_cold_start" for f in out.contributing_factors)


class TestBuildUserPrompt:
    def test_includes_all_evidence_sections(
        self,
        sample_feature_summary,
        sample_baseline_finding,
        sample_graph_finding,
    ):
        prompt = build_user_prompt(
            JudgeInput(
                feature_summary=sample_feature_summary,
                baseline_finding=sample_baseline_finding,
                graph_finding=sample_graph_finding,
            ),
            domain="fraud",
        )
        assert "Feature summary" in prompt
        assert "Behavioral baseline" in prompt
        assert "Graph reasoning" in prompt
        # The structured findings get serialized in the prompt body.
        assert "cardholder_count" in prompt or "card_count" in prompt
        assert "fraud_card_testing_ring" in prompt

    def test_cold_start_branch(self, sample_feature_summary):
        from deliberation.state import BaselineFinding

        cold = BaselineFinding(
            entity_id="new", domain="fraud", is_cold_start=True,
            event_count=0, embedding_dim=0,
        )
        prompt = build_user_prompt(
            JudgeInput(feature_summary=sample_feature_summary, baseline_finding=cold, graph_finding=None),
            domain="fraud",
        )
        assert "Cold-start" in prompt

    def test_missing_branches_marked(self, sample_feature_summary):
        prompt = build_user_prompt(
            JudgeInput(feature_summary=sample_feature_summary, baseline_finding=None, graph_finding=None),
            domain="fraud",
        )
        assert "Baseline unavailable" in prompt
        assert "Graph reasoning unavailable" in prompt


class TestToDecision:
    def test_stamps_metadata(self, sample_feature_summary):
        out = derive_fallback_decision(
            JudgeInput(feature_summary=sample_feature_summary, baseline_finding=None, graph_finding=None),
            domain="fraud",
        )
        decision = to_decision(out, latency_ms=123, judge_provider="ollama", judge_model="qwen3:8b")
        assert decision.latency_ms == 123
        assert decision.judge_provider == "ollama"
        assert decision.judge_model == "qwen3:8b"
