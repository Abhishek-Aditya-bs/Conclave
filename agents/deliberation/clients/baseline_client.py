"""Thin sync wrapper around the M3 ``BaselineService`` gRPC stub.

We do not retry inside the client: the orchestrator node decides what to
do on failure (degrade gracefully, mark cold-start, etc). The client only
sets a per-call deadline so a hung server can't pin the whole
deliberation.
"""
from __future__ import annotations

from contextlib import AbstractContextManager
from types import TracebackType

import grpc

from deliberation._proto import baseline_pb2, baseline_pb2_grpc
from deliberation.state import BaselineFinding, BaselineScore

# spec §6 M3: p99 lookup < 20ms; we keep an order-of-magnitude headroom
# so legit slowness still resolves, but a stuck server is short-circuited
# inside the 600ms deliberation budget.
DEFAULT_TIMEOUT_SECONDS = 0.5


class BaselineClient(AbstractContextManager["BaselineClient"]):
    """Sync gRPC client for M3.

    Owns its channel; close via ``__exit__`` or ``.close()``. One client
    per server lifetime is the intended usage — channels are thread-safe
    and pool internally.
    """

    def __init__(
        self,
        target: str,
        *,
        timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
        channel: grpc.Channel | None = None,
    ) -> None:
        self._target = target
        self._timeout = timeout_seconds
        # Allow injecting a pre-built channel for tests; otherwise build one.
        self._owns_channel = channel is None
        self._channel = channel or grpc.insecure_channel(target)
        self._stub = baseline_pb2_grpc.BaselineServiceStub(self._channel)

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

    def get_baseline(self, domain: str, entity_id: str) -> BaselineFinding:
        """Fetch the rolling baseline for ``entity_id`` in ``domain``.

        Translates the proto NotFound branch into a ``BaselineFinding``
        with ``is_cold_start=True``. Other gRPC errors propagate to the
        caller — the baseliner node catches them and decides what to do.
        """
        request = baseline_pb2.GetBaselineRequest(domain=domain, entity_id=entity_id)
        response = self._stub.GetBaseline(request, timeout=self._timeout)

        if response.HasField("not_found"):
            return BaselineFinding(
                entity_id=entity_id,
                domain=domain,
                is_cold_start=True,
                event_count=0,
                embedding_dim=0,
                note="no baseline yet (cold-start entity)",
            )

        baseline = response.baseline
        return BaselineFinding(
            entity_id=baseline.entity_id,
            domain=baseline.domain,
            is_cold_start=False,
            event_count=baseline.event_count,
            embedding_dim=len(baseline.embedding),
            last_updated_epoch_ms=baseline.last_updated_epoch_ms,
        )

    def score_event(
        self, domain: str, entity_id: str, enriched_event_json: str
    ) -> BaselineScore:
        """Score an event against ``entity_id``'s rolling baseline.

        M3 textualizes + embeds the event and compares it to the stored
        baseline via pgvector cosine similarity, returning a behavioral
        deviation score. Read-only — the baseline is not mutated. gRPC
        errors propagate to the caller; the baseliner node catches them and
        degrades to "no behavioral signal".
        """
        request = baseline_pb2.ScoreEventRequest(
            domain=domain,
            entity_id=entity_id,
            enriched_event_json=enriched_event_json,
        )
        response = self._stub.ScoreEvent(request, timeout=self._timeout)
        return BaselineScore(
            anomaly_score=response.anomaly_score,
            cosine_similarity=response.cosine_similarity,
            cold_start=response.cold_start,
            event_count=response.event_count,
        )
