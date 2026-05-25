"""gRPC server smoke test — real in-process server, real client, mocked graph."""
from __future__ import annotations

import socket
from concurrent import futures
from contextlib import contextmanager
from unittest.mock import MagicMock

import grpc
import pytest

from deliberation._proto import deliberation_pb2, deliberation_pb2_grpc
from deliberation.server import DeliberationService, decision_to_proto
from deliberation.state import (
    ContributingFactor,
    Decision,
    VerdictLabel,
)


def _free_port() -> int:
    """Find an OS-assigned free port we can bind to."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@contextmanager
def _running_server(servicer: DeliberationService):
    port = _free_port()
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=2))
    deliberation_pb2_grpc.add_DeliberationServiceServicer_to_server(servicer, server)
    server.add_insecure_port(f"127.0.0.1:{port}")
    server.start()
    try:
        yield f"127.0.0.1:{port}"
    finally:
        server.stop(grace=None)


class TestDeliberationServer:
    def _decision(self) -> Decision:
        return Decision(
            score=0.81,
            verdict_label=VerdictLabel.BLOCK,
            verdict_explanation_md="Block.",
            contributing_factors=(
                ContributingFactor(name="graph_ring_detected", weight=0.8, evidence="ring"),
            ),
            latency_ms=240,
            judge_provider="anthropic",
            judge_model="claude-haiku-4-5-20251001",
        )

    def test_decision_to_proto_roundtrip(self):
        proto = decision_to_proto(self._decision())
        assert proto.verdict_label == "BLOCK"
        assert proto.score == pytest.approx(0.81)
        assert proto.contributing_factors[0].name == "graph_ring_detected"
        assert proto.judge_model == "claude-haiku-4-5-20251001"

    def test_deliberate_happy_path(self):
        graph = MagicMock()
        graph.invoke.return_value = {"decision": self._decision(), "errors": []}
        with _running_server(DeliberationService(graph)) as addr:
            with grpc.insecure_channel(addr) as ch:
                stub = deliberation_pb2_grpc.DeliberationServiceStub(ch)
                response = stub.Deliberate(
                    deliberation_pb2.DeliberationRequest(
                        event_id="e-1",
                        domain="fraud",
                        baseline_entity_id="c",
                        graph_entity_ids=["c", "d"],
                        enriched_event_json="{}",
                    ),
                    timeout=5.0,
                )
        assert response.decision.verdict_label == "BLOCK"
        assert response.decision.latency_ms >= 0
        # The server overwrote latency_ms with its wallclock value.
        assert response.decision.judge_provider == "anthropic"

    def test_missing_event_id_rejected(self):
        graph = MagicMock()
        with _running_server(DeliberationService(graph)) as addr:
            with grpc.insecure_channel(addr) as ch:
                stub = deliberation_pb2_grpc.DeliberationServiceStub(ch)
                with pytest.raises(grpc.RpcError) as exc_info:
                    stub.Deliberate(
                        deliberation_pb2.DeliberationRequest(
                            event_id="",
                            domain="fraud",
                            baseline_entity_id="c",
                            enriched_event_json="{}",
                        ),
                        timeout=5.0,
                    )
        assert exc_info.value.code() == grpc.StatusCode.INVALID_ARGUMENT
        graph.invoke.assert_not_called()

    def test_invalid_domain_rejected(self):
        graph = MagicMock()
        with _running_server(DeliberationService(graph)) as addr:
            with grpc.insecure_channel(addr) as ch:
                stub = deliberation_pb2_grpc.DeliberationServiceStub(ch)
                with pytest.raises(grpc.RpcError) as exc_info:
                    stub.Deliberate(
                        deliberation_pb2.DeliberationRequest(
                            event_id="e",
                            domain="foo",
                            baseline_entity_id="c",
                            enriched_event_json="{}",
                        ),
                        timeout=5.0,
                    )
        assert exc_info.value.code() == grpc.StatusCode.INVALID_ARGUMENT

    def test_graph_crash_surfaces_as_internal(self):
        graph = MagicMock()
        graph.invoke.side_effect = RuntimeError("boom")
        with _running_server(DeliberationService(graph)) as addr:
            with grpc.insecure_channel(addr) as ch:
                stub = deliberation_pb2_grpc.DeliberationServiceStub(ch)
                with pytest.raises(grpc.RpcError) as exc_info:
                    stub.Deliberate(
                        deliberation_pb2.DeliberationRequest(
                            event_id="e",
                            domain="fraud",
                            baseline_entity_id="c",
                            enriched_event_json="{}",
                        ),
                        timeout=5.0,
                    )
        assert exc_info.value.code() == grpc.StatusCode.INTERNAL

    def test_missing_decision_surfaces_as_internal(self):
        graph = MagicMock()
        graph.invoke.return_value = {"errors": ["something bad"]}  # no 'decision' key
        with _running_server(DeliberationService(graph)) as addr:
            with grpc.insecure_channel(addr) as ch:
                stub = deliberation_pb2_grpc.DeliberationServiceStub(ch)
                with pytest.raises(grpc.RpcError) as exc_info:
                    stub.Deliberate(
                        deliberation_pb2.DeliberationRequest(
                            event_id="e",
                            domain="fraud",
                            baseline_entity_id="c",
                            enriched_event_json="{}",
                        ),
                        timeout=5.0,
                    )
        assert exc_info.value.code() == grpc.StatusCode.INTERNAL
