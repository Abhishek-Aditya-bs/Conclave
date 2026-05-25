"""GraphClient tests with the generated stub mocked."""
from __future__ import annotations

from unittest.mock import MagicMock, patch

import grpc

from deliberation._proto import graph_pb2
from deliberation.clients import GraphClient


class TestGraphClient:
    def test_translates_finding_with_json_attributes(self):
        stub = MagicMock()
        stub.ExecuteTemplate.return_value = graph_pb2.ExecuteTemplateResponse(
            finding=graph_pb2.GraphFinding(
                template_name="fraud_card_testing_ring",
                root_entity_id="dev-1",
                domain="fraud",
                attributes_json='{"cardholder_count": 7, "card_count": 12}',
                risk_signal=0.82,
                query_latency_ms=5,
            )
        )
        with patch(
            "deliberation.clients.graph_client.graph_pb2_grpc.GraphReasonerServiceStub",
            return_value=stub,
        ):
            client = GraphClient(target="ignored", channel=MagicMock())
            finding = client.execute_template(
                template_name="fraud_card_testing_ring",
                params={"device_fingerprint": "dev-1"},
            )

        assert finding is not None
        assert finding.risk_signal == 0.82
        assert finding.attributes == {"cardholder_count": 7, "card_count": 12}

    def test_template_not_found_returns_none(self):
        stub = MagicMock()
        stub.ExecuteTemplate.return_value = graph_pb2.ExecuteTemplateResponse(
            error=graph_pb2.TemplateNotFound(template_name="nope", message="x"),
        )
        with patch(
            "deliberation.clients.graph_client.graph_pb2_grpc.GraphReasonerServiceStub",
            return_value=stub,
        ):
            client = GraphClient(target="ignored", channel=MagicMock())
            assert client.execute_template("nope", {}) is None

    def test_empty_attributes_json_yields_empty_dict(self):
        stub = MagicMock()
        stub.ExecuteTemplate.return_value = graph_pb2.ExecuteTemplateResponse(
            finding=graph_pb2.GraphFinding(
                template_name="t", root_entity_id="r", domain="fraud",
                attributes_json="", risk_signal=0.0, query_latency_ms=1,
            )
        )
        with patch(
            "deliberation.clients.graph_client.graph_pb2_grpc.GraphReasonerServiceStub",
            return_value=stub,
        ):
            client = GraphClient(target="ignored", channel=MagicMock())
            finding = client.execute_template("t", {})
        assert finding is not None
        assert finding.attributes == {}

    def test_invalid_attributes_json_preserves_raw(self):
        stub = MagicMock()
        stub.ExecuteTemplate.return_value = graph_pb2.ExecuteTemplateResponse(
            finding=graph_pb2.GraphFinding(
                template_name="t", root_entity_id="r", domain="fraud",
                attributes_json="not json", risk_signal=0.0, query_latency_ms=1,
            )
        )
        with patch(
            "deliberation.clients.graph_client.graph_pb2_grpc.GraphReasonerServiceStub",
            return_value=stub,
        ):
            client = GraphClient(target="ignored", channel=MagicMock())
            finding = client.execute_template("t", {})
        assert finding is not None
        assert finding.attributes == {"_unparseable": "not json"}

    def test_rpc_error_propagates(self):
        stub = MagicMock()
        stub.ExecuteTemplate.side_effect = grpc.RpcError("network")
        with patch(
            "deliberation.clients.graph_client.graph_pb2_grpc.GraphReasonerServiceStub",
            return_value=stub,
        ):
            client = GraphClient(target="ignored", channel=MagicMock())
            try:
                client.execute_template("t", {})
            except grpc.RpcError:
                pass
            else:  # pragma: no cover
                raise AssertionError("expected RpcError")

    def test_close_owned_channel(self):
        with patch("deliberation.clients.graph_client.grpc.insecure_channel") as factory:
            ch = MagicMock()
            factory.return_value = ch
            with GraphClient(target="x:1"):
                pass
            ch.close.assert_called_once()
