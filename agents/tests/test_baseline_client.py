"""BaselineClient tests with the generated stub mocked."""
from __future__ import annotations

from unittest.mock import MagicMock, patch

import grpc

from deliberation._proto import baseline_pb2
from deliberation.clients import BaselineClient


class TestBaselineClient:
    def test_translates_baseline_response(self):
        stub = MagicMock()
        stub.GetBaseline.return_value = baseline_pb2.GetBaselineResponse(
            baseline=baseline_pb2.Baseline(
                entity_id="c-9",
                domain="fraud",
                embedding=[0.0] * 384,
                event_count=42,
                last_updated_epoch_ms=1716624000000,
            )
        )
        with patch(
            "deliberation.clients.baseline_client.baseline_pb2_grpc.BaselineServiceStub",
            return_value=stub,
        ):
            client = BaselineClient(target="ignored", channel=MagicMock())
            finding = client.get_baseline(domain="fraud", entity_id="c-9")

        assert finding.entity_id == "c-9"
        assert finding.event_count == 42
        assert finding.embedding_dim == 384
        assert finding.is_cold_start is False

    def test_translates_not_found_to_cold_start(self):
        stub = MagicMock()
        stub.GetBaseline.return_value = baseline_pb2.GetBaselineResponse(
            not_found=baseline_pb2.NotFound(message="nope")
        )
        with patch(
            "deliberation.clients.baseline_client.baseline_pb2_grpc.BaselineServiceStub",
            return_value=stub,
        ):
            client = BaselineClient(target="ignored", channel=MagicMock())
            finding = client.get_baseline(domain="fraud", entity_id="c-new")

        assert finding.is_cold_start is True
        assert finding.event_count == 0
        assert finding.embedding_dim == 0
        assert "cold-start" in finding.note

    def test_passes_timeout_to_stub(self):
        stub = MagicMock()
        stub.GetBaseline.return_value = baseline_pb2.GetBaselineResponse(
            not_found=baseline_pb2.NotFound(message="x")
        )
        with patch(
            "deliberation.clients.baseline_client.baseline_pb2_grpc.BaselineServiceStub",
            return_value=stub,
        ):
            client = BaselineClient(
                target="ignored", channel=MagicMock(), timeout_seconds=0.25
            )
            client.get_baseline(domain="fraud", entity_id="x")
        kwargs = stub.GetBaseline.call_args.kwargs
        assert kwargs["timeout"] == 0.25

    def test_rpc_error_propagates(self):
        stub = MagicMock()
        rpc_err = grpc.RpcError("boom")
        stub.GetBaseline.side_effect = rpc_err
        with patch(
            "deliberation.clients.baseline_client.baseline_pb2_grpc.BaselineServiceStub",
            return_value=stub,
        ):
            client = BaselineClient(target="ignored", channel=MagicMock())
            try:
                client.get_baseline(domain="fraud", entity_id="x")
            except grpc.RpcError:
                pass
            else:  # pragma: no cover — assertion via raise
                raise AssertionError("expected RpcError to propagate")

    def test_context_manager_closes_owned_channel(self):
        ch = MagicMock()
        client = BaselineClient(target="ignored", channel=ch)
        # Channel passed in: client does not own it, must not close.
        with client:
            pass
        ch.close.assert_not_called()

    def test_close_owned_channel(self):
        with patch(
            "deliberation.clients.baseline_client.grpc.insecure_channel"
        ) as factory:
            ch = MagicMock()
            factory.return_value = ch
            client = BaselineClient(target="x:1")
            client.close()
            ch.close.assert_called_once()
