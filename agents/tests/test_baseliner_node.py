"""Baseliner-node tests — mocked M3 gRPC client.

The baseliner now calls M3's ScoreEvent (not GetBaseline): it scores the current
event against the entity's rolling baseline and surfaces the cosine similarity +
anomaly score to the judge.
"""
from __future__ import annotations

from unittest.mock import MagicMock

import grpc

from deliberation.nodes.baseliner import baseliner_node, make_baseliner_node
from deliberation.state import BaselineScore


def _state(**overrides):
    base = {
        "event_id": "e",
        "domain": "fraud",
        "baseline_entity_id": "cardholder-9",
        "graph_entity_ids": [],
        "enriched_event_json": '{"amountMinor": 4200}',
    }
    base.update(overrides)
    return base


class TestBaselinerNode:
    def test_no_client_skipped(self):
        # The module-level fallback has no client; it should no-op safely.
        result = baseliner_node(_state())
        assert result["baseline_finding"] is None
        assert "no client" in result["errors"][0]

    def test_happy_path(self):
        client = MagicMock()
        client.score_event.return_value = BaselineScore(
            anomaly_score=0.12,
            cosine_similarity=0.88,
            cold_start=False,
            event_count=12,
        )
        node = make_baseliner_node(client)
        result = node(_state())
        finding = result["baseline_finding"]
        assert finding.event_count == 12
        assert finding.is_cold_start is False
        assert finding.cosine_similarity == 0.88
        assert finding.anomaly_score == 0.12
        assert finding.embedding_dim == 384
        client.score_event.assert_called_once_with(
            domain="fraud",
            entity_id="cardholder-9",
            enriched_event_json='{"amountMinor": 4200}',
        )
        assert "errors" not in result  # success path emits no errors

    def test_cold_start_finding(self):
        client = MagicMock()
        client.score_event.return_value = BaselineScore(
            anomaly_score=0.5,
            cosine_similarity=0.0,
            cold_start=True,
            event_count=0,
        )
        node = make_baseliner_node(client)
        finding = node(_state())["baseline_finding"]
        assert finding.is_cold_start is True
        # Cosine is meaningless with no baseline → not surfaced to the judge.
        assert finding.cosine_similarity is None
        assert finding.embedding_dim == 0
        assert finding.anomaly_score == 0.5
        assert "cold-start" in finding.note

    def test_empty_entity_id_short_circuits(self):
        client = MagicMock()
        node = make_baseliner_node(client)
        result = node(_state(baseline_entity_id=""))
        assert result["baseline_finding"] is None
        assert "empty baseline_entity_id" in result["errors"][0]
        client.score_event.assert_not_called()

    def test_grpc_error_degrades_gracefully(self):
        client = MagicMock()
        rpc_err = grpc.RpcError()
        rpc_err.code = lambda: grpc.StatusCode.UNAVAILABLE  # type: ignore[method-assign]
        client.score_event.side_effect = rpc_err
        node = make_baseliner_node(client)
        result = node(_state())
        assert result["baseline_finding"] is None
        assert "gRPC error" in result["errors"][0]

    def test_unexpected_exception_degrades_gracefully(self):
        client = MagicMock()
        client.score_event.side_effect = RuntimeError("boom")
        node = make_baseliner_node(client)
        result = node(_state())
        assert result["baseline_finding"] is None
        assert "RuntimeError" in result["errors"][0]
