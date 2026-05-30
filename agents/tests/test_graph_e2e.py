"""End-to-end LangGraph invocation with all I/O mocked.

This is the canonical "M5 done" assertion the spec asks for: given a
canned enriched event, the graph returns a Decision with all fields
populated.
"""
from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from deliberation.clients import BaselineClient, GraphClient
from deliberation.graph import build_graph
from deliberation.llm.provider import JudgeOutput
from deliberation.state import (
    BaselineScore,
    ContributingFactor,
    GraphFinding,
    VerdictLabel,
)


def _make_clients(*, baseline_score, graph_finding):
    baseline_client = MagicMock(spec=BaselineClient)
    baseline_client.score_event.return_value = baseline_score
    graph_client = MagicMock(spec=GraphClient)
    graph_client.execute_template.return_value = graph_finding
    return baseline_client, graph_client


def _make_provider(name: str, model: str, *, score: float, label: VerdictLabel):
    provider = MagicMock()
    provider.name = name
    provider.model = model
    provider.produce_decision.return_value = JudgeOutput(
        score=score,
        verdict_label=label,
        verdict_explanation_md=f"{label.value} verdict.",
        contributing_factors=(
            ContributingFactor(name="graph_signal", weight=0.5, evidence="signal"),
        ),
    )
    return provider


class TestE2EAnthropic:
    def test_fraud_block_decision(self, fraud_event_json):
        baseline_client, graph_client = _make_clients(
            baseline_score=BaselineScore(
                anomaly_score=0.15,
                cosine_similarity=0.90,
                cold_start=False,
                event_count=120,
            ),
            graph_finding=GraphFinding(
                template_name="fraud_card_testing_ring",
                root_entity_id="dev-suspect",
                domain="fraud",
                attributes={"cardholder_count": 7, "card_count": 14},
                risk_signal=0.92,
                query_latency_ms=4,
            ),
        )
        provider = _make_provider(
            "anthropic", "claude-haiku-4-5-20251001",
            score=0.92, label=VerdictLabel.BLOCK,
        )

        graph = build_graph(
            baseline_client=baseline_client,
            graph_client=graph_client,
            llm_provider=provider,
        )
        out = graph.invoke({
            "event_id": "evt-fraud-1",
            "domain": "fraud",
            "baseline_entity_id": "cardholder-9",
            "graph_entity_ids": ["cardholder-9", "dev-suspect", "203.0.113.5", "mer-1"],
            "enriched_event_json": fraud_event_json,
            "errors": [],
        })

        decision = out["decision"]
        assert decision.verdict_label == VerdictLabel.BLOCK
        assert decision.score == pytest.approx(0.92)
        assert decision.judge_provider == "anthropic"
        assert decision.judge_model == "claude-haiku-4-5-20251001"
        assert decision.contributing_factors  # non-empty
        # Both upstream nodes ran in parallel; both findings ended up in state.
        assert out["baseline_finding"].event_count == 120
        assert out["graph_finding"].risk_signal == 0.92
        # The judge saw both findings.
        ji = provider.produce_decision.call_args.args[0]
        assert ji.baseline_finding.event_count == 120
        assert ji.graph_finding.risk_signal == 0.92


class TestE2EOllama:
    def test_security_review_decision(self, security_event_json):
        baseline_client, graph_client = _make_clients(
            baseline_score=BaselineScore(
                anomaly_score=0.5,
                cosine_similarity=0.0,
                cold_start=True,
                event_count=0,
            ),
            graph_finding=GraphFinding(
                template_name="security_lateral_movement",
                root_entity_id="alice@corp",
                domain="security",
                attributes={"host_count": 6},
                risk_signal=0.55,
                query_latency_ms=3,
            ),
        )
        provider = _make_provider(
            "ollama", "qwen3:8b",
            score=0.55, label=VerdictLabel.REVIEW,
        )

        graph = build_graph(
            baseline_client=baseline_client,
            graph_client=graph_client,
            llm_provider=provider,
        )
        out = graph.invoke({
            "event_id": "evt-sec-1",
            "domain": "security",
            "baseline_entity_id": "alice@corp",
            "graph_entity_ids": ["alice@corp", "host-prod-12", "10.0.7.42", "prod-db-1"],
            "enriched_event_json": security_event_json,
            "errors": [],
        })

        decision = out["decision"]
        assert decision.verdict_label == VerdictLabel.REVIEW
        assert decision.judge_provider == "ollama"
        assert decision.judge_model == "qwen3:8b"


class TestE2EDegradation:
    def test_both_services_down_still_returns_decision(self, fraud_event_json):
        import grpc

        baseline_client = MagicMock(spec=BaselineClient)
        rpc_err = grpc.RpcError()
        rpc_err.code = lambda: grpc.StatusCode.UNAVAILABLE  # type: ignore[method-assign]
        baseline_client.score_event.side_effect = rpc_err

        graph_client = MagicMock(spec=GraphClient)
        graph_client.execute_template.side_effect = rpc_err

        provider = _make_provider("anthropic", "claude-haiku-4-5-20251001",
                                  score=0.20, label=VerdictLabel.ALLOW)
        graph = build_graph(
            baseline_client=baseline_client,
            graph_client=graph_client,
            llm_provider=provider,
        )

        out = graph.invoke({
            "event_id": "evt-fraud-1",
            "domain": "fraud",
            "baseline_entity_id": "cardholder-9",
            "graph_entity_ids": ["cardholder-9", "dev-suspect"],
            "enriched_event_json": fraud_event_json,
            "errors": [],
        })

        # The graph still produces a Decision; both per-node errors recorded.
        assert out["decision"].verdict_label == VerdictLabel.ALLOW
        assert out["baseline_finding"] is None
        assert out["graph_finding"] is None
        assert len(out["errors"]) >= 2  # one per failed branch
