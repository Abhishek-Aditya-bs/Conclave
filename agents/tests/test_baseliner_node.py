"""Baseliner-node tests — mocked M3 gRPC client."""
from __future__ import annotations

from unittest.mock import MagicMock

import grpc

from deliberation.nodes.baseliner import baseliner_node, make_baseliner_node
from deliberation.state import BaselineFinding


def _state(**overrides):
    base = {
        "event_id": "e",
        "domain": "fraud",
        "baseline_entity_id": "cardholder-9",
        "graph_entity_ids": [],
        "enriched_event_json": "{}",
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
        client.get_baseline.return_value = BaselineFinding(
            entity_id="cardholder-9",
            domain="fraud",
            is_cold_start=False,
            event_count=12,
            embedding_dim=384,
        )
        node = make_baseliner_node(client)
        result = node(_state())
        assert result["baseline_finding"].event_count == 12
        client.get_baseline.assert_called_once_with(domain="fraud", entity_id="cardholder-9")
        assert "errors" not in result  # success path emits no errors

    def test_empty_entity_id_short_circuits(self):
        client = MagicMock()
        node = make_baseliner_node(client)
        result = node(_state(baseline_entity_id=""))
        assert result["baseline_finding"] is None
        assert "empty baseline_entity_id" in result["errors"][0]
        client.get_baseline.assert_not_called()

    def test_grpc_error_degrades_gracefully(self):
        client = MagicMock()
        rpc_err = grpc.RpcError()
        rpc_err.code = lambda: grpc.StatusCode.UNAVAILABLE  # type: ignore[method-assign]
        client.get_baseline.side_effect = rpc_err
        node = make_baseliner_node(client)
        result = node(_state())
        assert result["baseline_finding"] is None
        assert "gRPC error" in result["errors"][0]

    def test_unexpected_exception_degrades_gracefully(self):
        client = MagicMock()
        client.get_baseline.side_effect = RuntimeError("boom")
        node = make_baseliner_node(client)
        result = node(_state())
        assert result["baseline_finding"] is None
        assert "RuntimeError" in result["errors"][0]
