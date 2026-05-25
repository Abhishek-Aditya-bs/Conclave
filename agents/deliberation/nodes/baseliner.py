"""Behavioral baseliner node — calls M3 over gRPC.

If M3 is unreachable or returns an error we degrade gracefully: the
node emits a ``baseline_finding=None`` and appends an entry to
``errors``. The judge node treats ``None`` as "no behavioral signal" and
proceeds with the graph + feature evidence alone.
"""
from __future__ import annotations

import logging
from typing import Any

import grpc

from deliberation.clients import BaselineClient
from deliberation.state import DeliberationState

_LOG = logging.getLogger(__name__)


def make_baseliner_node(client: BaselineClient | None):
    """Bind a client into a LangGraph-compatible node.

    Returns a closure rather than registering the client globally so
    tests can substitute mocks per-graph instead of per-process.
    Passing ``client=None`` makes the node a no-op (useful when M3 is
    not yet wired in some smoke tests).
    """

    def baseliner_node(state: DeliberationState) -> dict[str, Any]:
        if client is None:
            return {
                "baseline_finding": None,
                "errors": ["baseliner: no client configured (skipped)"],
            }

        domain = state["domain"]
        entity_id = state["baseline_entity_id"]
        if not entity_id:
            return {
                "baseline_finding": None,
                "errors": ["baseliner: empty baseline_entity_id"],
            }

        try:
            finding = client.get_baseline(domain=domain, entity_id=entity_id)
            return {"baseline_finding": finding}
        except grpc.RpcError as exc:
            code = getattr(exc, "code", lambda: None)()
            _LOG.warning(
                "baseliner: M3 gRPC failure (code=%s) for entity=%s domain=%s",
                code,
                entity_id,
                domain,
            )
            return {
                "baseline_finding": None,
                "errors": [f"baseliner: gRPC error {code}: {exc}"],
            }
        except Exception as exc:  # noqa: BLE001 — defensive, M5 must never crash mid-graph
            _LOG.exception("baseliner: unexpected failure")
            return {
                "baseline_finding": None,
                "errors": [f"baseliner: {type(exc).__name__}: {exc}"],
            }

    return baseliner_node


# Module-level fallback for the rare case where the graph is constructed
# without a client (e.g. in unit tests of the wiring itself).
baseliner_node = make_baseliner_node(None)
