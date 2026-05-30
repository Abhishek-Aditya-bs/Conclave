"""Thin sync wrapper around the ``GraphReasonerService`` gRPC stub.

The graph service offers ``ListTemplates`` (discovery) and ``ExecuteTemplate``
(the workhorse). The deliberation graph picks the right template per
domain — see ``deliberation/nodes/graph_reasoner.py``.
"""
from __future__ import annotations

import json
import os
from contextlib import AbstractContextManager
from types import TracebackType
from typing import Any

import grpc

from deliberation._proto import graph_pb2, graph_pb2_grpc
from deliberation.state import GraphFinding

# Graph p99 < 50ms, but the gRPC service can be slow on cold start, so the
# deadline must leave headroom. The default is env-overridable via
# GRAPH_DEADLINE_MS (compose sets a generous 10s); when unset we keep the
# historical 1.0s so existing behavior/tests are stable.
_FALLBACK_TIMEOUT_SECONDS = 1.0


def _default_timeout_seconds() -> float:
    raw = os.environ.get("GRAPH_DEADLINE_MS")
    if raw:
        try:
            return float(raw) / 1000.0
        except ValueError:
            pass
    return _FALLBACK_TIMEOUT_SECONDS


DEFAULT_TIMEOUT_SECONDS = _default_timeout_seconds()


class GraphClient(AbstractContextManager["GraphClient"]):
    """Sync gRPC client for the graph service."""

    def __init__(
        self,
        target: str,
        *,
        timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
        channel: grpc.Channel | None = None,
    ) -> None:
        self._target = target
        self._timeout = timeout_seconds
        self._owns_channel = channel is None
        self._channel = channel or grpc.insecure_channel(target)
        self._stub = graph_pb2_grpc.GraphReasonerServiceStub(self._channel)

    # -- lifecycle -----------------------------------------------------

    def close(self) -> None:
        if self._owns_channel:
            self._channel.close()

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    # -- API -----------------------------------------------------------

    def execute_template(
        self, template_name: str, params: dict[str, str]
    ) -> GraphFinding | None:
        """Run ``template_name`` with ``params``.

        Returns ``None`` if the template is unregistered on the server
        (the proto's ``TemplateNotFound`` branch) — the graph reasoner
        node treats that as "no graph signal" rather than an error.
        Other gRPC errors propagate.
        """
        request = graph_pb2.ExecuteTemplateRequest(
            template_name=template_name,
            params=params,
        )
        response = self._stub.ExecuteTemplate(request, timeout=self._timeout)

        if response.HasField("error"):
            return None

        finding = response.finding
        # The proto attributes are a JSON string per the graph service's contract (see
        # graph.proto for why a Struct was rejected).
        attributes: dict[str, Any]
        try:
            attributes = json.loads(finding.attributes_json) if finding.attributes_json else {}
        except json.JSONDecodeError:
            attributes = {"_unparseable": finding.attributes_json}

        return GraphFinding(
            template_name=finding.template_name,
            root_entity_id=finding.root_entity_id,
            domain=finding.domain,
            attributes=attributes,
            risk_signal=finding.risk_signal,
            query_latency_ms=finding.query_latency_ms,
        )
