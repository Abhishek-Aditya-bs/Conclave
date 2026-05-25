"""gRPC servicer that drives the LangGraph for one event.

Translates the proto request into a ``DeliberationState`` seed, invokes
the compiled graph, then translates the final ``Decision`` back into
the proto response. The graph itself is compiled once at construction
and reused across calls — LangGraph's compiled graph object is
thread-safe for invocation.
"""
from __future__ import annotations

import logging
import time
from typing import Any

import grpc

from deliberation._proto import deliberation_pb2, deliberation_pb2_grpc
from deliberation.state import Decision, DeliberationState

_LOG = logging.getLogger(__name__)


def decision_to_proto(decision: Decision) -> deliberation_pb2.Decision:
    """Translate the Python ``Decision`` into the wire form."""
    proto = deliberation_pb2.Decision(
        score=decision.score,
        verdict_label=decision.verdict_label.value,
        verdict_explanation_md=decision.verdict_explanation_md,
        latency_ms=decision.latency_ms,
        judge_provider=decision.judge_provider,
        judge_model=decision.judge_model,
    )
    for factor in decision.contributing_factors:
        proto.contributing_factors.add(
            name=factor.name,
            weight=factor.weight,
            evidence=factor.evidence,
        )
    return proto


class DeliberationService(deliberation_pb2_grpc.DeliberationServiceServicer):
    """Thin servicer; one compiled graph, one call per request."""

    def __init__(self, compiled_graph: Any) -> None:
        self._graph = compiled_graph

    def Deliberate(
        self,
        request: deliberation_pb2.DeliberationRequest,
        context: grpc.ServicerContext,
    ) -> deliberation_pb2.DeliberationResponse:
        # Defensive validation — the orchestrator (M6) should always send these.
        if not request.event_id:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, "event_id is required")
        if request.domain not in ("fraud", "security"):
            context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                f"domain must be 'fraud' or 'security'; got '{request.domain}'",
            )

        state: DeliberationState = {
            "event_id": request.event_id,
            "domain": request.domain,
            "baseline_entity_id": request.baseline_entity_id,
            "graph_entity_ids": list(request.graph_entity_ids),
            "enriched_event_json": request.enriched_event_json,
            "errors": [],
        }

        start = time.perf_counter()
        try:
            final = self._graph.invoke(state)
        except Exception as exc:  # noqa: BLE001 — protect the server thread
            _LOG.exception("Deliberation graph crashed for event=%s", request.event_id)
            context.abort(grpc.StatusCode.INTERNAL, f"graph crashed: {exc}")
        wallclock_ms = int((time.perf_counter() - start) * 1000)

        decision = final.get("decision")
        if decision is None:
            context.abort(
                grpc.StatusCode.INTERNAL,
                "graph completed without producing a decision",
            )

        # Replace the judge's intra-node latency with the full graph wallclock
        # so the response reflects what the caller actually paid for.
        decision = Decision(
            score=decision.score,
            verdict_label=decision.verdict_label,
            verdict_explanation_md=decision.verdict_explanation_md,
            contributing_factors=decision.contributing_factors,
            latency_ms=wallclock_ms,
            judge_provider=decision.judge_provider,
            judge_model=decision.judge_model,
        )

        errors = final.get("errors") or []
        if errors:
            _LOG.info(
                "Deliberation for event=%s completed with %d soft errors: %s",
                request.event_id,
                len(errors),
                errors,
            )

        return deliberation_pb2.DeliberationResponse(decision=decision_to_proto(decision))
