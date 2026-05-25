"""Graph-reasoner-node tests — mocked M4 gRPC client."""
from __future__ import annotations

from unittest.mock import MagicMock

import grpc

from deliberation.nodes.graph_reasoner import (
    graph_reasoner_node,
    make_graph_reasoner_node,
)
from deliberation.state import GraphFinding


def _state(**overrides):
    base = {
        "event_id": "e",
        "domain": "fraud",
        "baseline_entity_id": "cardholder-9",
        "graph_entity_ids": ["cardholder-9", "dev-suspect", "203.0.113.5", "mer-1"],
        "enriched_event_json": "{}",
    }
    base.update(overrides)
    return base


class TestGraphReasonerNode:
    def test_no_client_skipped(self):
        result = graph_reasoner_node(_state())
        assert result["graph_finding"] is None
        assert "no client" in result["errors"][0]

    def test_fraud_happy_path_roots_on_device(self):
        client = MagicMock()
        client.execute_template.return_value = GraphFinding(
            template_name="fraud_card_testing_ring",
            root_entity_id="dev-suspect",
            domain="fraud",
            attributes={"cardholder_count": 7},
            risk_signal=0.85,
            query_latency_ms=5,
        )
        node = make_graph_reasoner_node(client)
        result = node(_state())
        assert result["graph_finding"].risk_signal == 0.85
        # M4 template + param key for fraud — see configs/fraud/enriched-schema.avsc layout.
        client.execute_template.assert_called_once_with(
            template_name="fraud_card_testing_ring",
            params={"device_fingerprint": "dev-suspect"},
        )

    def test_security_happy_path_roots_on_principal(self):
        client = MagicMock()
        client.execute_template.return_value = GraphFinding(
            template_name="security_lateral_movement",
            root_entity_id="alice@corp",
            domain="security",
            attributes={"host_count": 6},
            risk_signal=0.7,
            query_latency_ms=4,
        )
        node = make_graph_reasoner_node(client)
        state = _state(
            domain="security",
            baseline_entity_id="alice@corp",
            graph_entity_ids=["alice@corp", "host-prod-12", "10.0.7.42", "prod-db-1"],
        )
        node(state)
        client.execute_template.assert_called_once_with(
            template_name="security_lateral_movement",
            params={"principal_id": "alice@corp"},
        )

    def test_unknown_domain(self):
        client = MagicMock()
        node = make_graph_reasoner_node(client)
        result = node(_state(domain="bogus"))
        assert result["graph_finding"] is None
        assert "unknown domain" in result["errors"][0]
        client.execute_template.assert_not_called()

    def test_no_entity_ids(self):
        client = MagicMock()
        node = make_graph_reasoner_node(client)
        result = node(_state(graph_entity_ids=[]))
        assert result["graph_finding"] is None
        assert "no root entity" in result["errors"][0]

    def test_template_not_registered(self):
        client = MagicMock()
        client.execute_template.return_value = None  # mirrors proto TemplateNotFound branch
        node = make_graph_reasoner_node(client)
        result = node(_state())
        assert result["graph_finding"] is None
        assert "not registered" in result["errors"][0]

    def test_grpc_error_degrades(self):
        client = MagicMock()
        rpc_err = grpc.RpcError()
        rpc_err.code = lambda: grpc.StatusCode.DEADLINE_EXCEEDED  # type: ignore[method-assign]
        client.execute_template.side_effect = rpc_err
        node = make_graph_reasoner_node(client)
        result = node(_state())
        assert result["graph_finding"] is None
        assert "gRPC error" in result["errors"][0]

    def test_unexpected_exception_degrades(self):
        client = MagicMock()
        client.execute_template.side_effect = ValueError("bad input")
        node = make_graph_reasoner_node(client)
        result = node(_state())
        assert result["graph_finding"] is None
        assert "ValueError" in result["errors"][0]

    def test_fraud_falls_back_to_first_id_when_only_one(self):
        client = MagicMock()
        client.execute_template.return_value = GraphFinding(
            template_name="fraud_card_testing_ring",
            root_entity_id="just-one",
            domain="fraud",
            attributes={},
            risk_signal=0.0,
            query_latency_ms=1,
        )
        node = make_graph_reasoner_node(client)
        node(_state(graph_entity_ids=["just-one"]))
        client.execute_template.assert_called_once_with(
            template_name="fraud_card_testing_ring",
            params={"device_fingerprint": "just-one"},
        )
