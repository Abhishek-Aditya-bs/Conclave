"""Graph reasoner node — calls M4 over gRPC.

Picks ONE template per domain (the "risk" template; the neighborhood /
descriptive templates are not invoked in the hot path because they
don't carry a risk_signal). The judge sees the resulting finding plus
the M4 risk_signal as one input among several.

Template selection:
  * fraud    → ``fraud_card_testing_ring`` (rooted on the device fingerprint).
  * security → ``security_lateral_movement`` (rooted on the principal).

Both are depth-bounded by M4 (`*1..2`) so latency stays predictable.
"""
from __future__ import annotations

import logging
from typing import Any

import grpc

from deliberation.clients import GraphClient
from deliberation.state import DeliberationState

_LOG = logging.getLogger(__name__)


# Per-domain template + the state key (or graph_entity_ids index) we root on.
# fraud: device fingerprint is graph_entity_ids[1] in M2's EnrichedPaymentEvent
#        emission order (cardholder, device, ip, merchant). See
#        configs/fraud/enriched-schema.avsc.
# security: principal is graph_entity_ids[0] in M2's EnrichedAuthEvent
#        emission order (principal, host, ip, [target]). See
#        configs/security/enriched-schema.avsc.
_DOMAIN_TEMPLATE: dict[str, tuple[str, str]] = {
    "fraud": ("fraud_card_testing_ring", "device_fingerprint"),
    "security": ("security_lateral_movement", "principal_id"),
}


def make_graph_reasoner_node(client: GraphClient | None):
    def graph_reasoner_node(state: DeliberationState) -> dict[str, Any]:
        if client is None:
            return {
                "graph_finding": None,
                "errors": ["graph_reasoner: no client configured (skipped)"],
            }

        domain = state["domain"]
        if domain not in _DOMAIN_TEMPLATE:
            return {
                "graph_finding": None,
                "errors": [f"graph_reasoner: unknown domain '{domain}'"],
            }

        template_name, param_key = _DOMAIN_TEMPLATE[domain]
        entity_ids = state.get("graph_entity_ids") or []
        root_entity = _pick_root(domain, entity_ids, state)
        if not root_entity:
            return {
                "graph_finding": None,
                "errors": [
                    f"graph_reasoner: no root entity for domain={domain} "
                    f"(graph_entity_ids={entity_ids!r})"
                ],
            }

        try:
            finding = client.execute_template(
                template_name=template_name,
                params={param_key: root_entity},
            )
            if finding is None:
                return {
                    "graph_finding": None,
                    "errors": [
                        f"graph_reasoner: template '{template_name}' not registered"
                    ],
                }
            return {"graph_finding": finding}
        except grpc.RpcError as exc:
            code = getattr(exc, "code", lambda: None)()
            _LOG.warning(
                "graph_reasoner: M4 gRPC failure (code=%s) for template=%s root=%s",
                code,
                template_name,
                root_entity,
            )
            return {
                "graph_finding": None,
                "errors": [f"graph_reasoner: gRPC error {code}: {exc}"],
            }
        except Exception as exc:  # noqa: BLE001
            _LOG.exception("graph_reasoner: unexpected failure")
            return {
                "graph_finding": None,
                "errors": [f"graph_reasoner: {type(exc).__name__}: {exc}"],
            }

    return graph_reasoner_node


def _pick_root(domain: str, entity_ids: list[str], state: DeliberationState) -> str:
    """Pick the right node from ``graph_entity_ids`` for the chosen template.

    Layouts (from M2):
      fraud:    [cardholderId, deviceFingerprint, ipAddress, merchantId]
      security: [principalId, hostId, sourceIp, (targetResource if present)]

    Fraud's card-testing-ring template is rooted on the device fingerprint
    (the device that touched many cardholders). Security's
    lateral-movement template is rooted on the principal (the identity
    hitting many hosts).
    """
    if not entity_ids:
        return ""
    if domain == "fraud":
        return entity_ids[1] if len(entity_ids) >= 2 else entity_ids[0]
    if domain == "security":
        return entity_ids[0]
    return entity_ids[0]


# Module-level fallback for the rare case where the graph is constructed
# without a client.
graph_reasoner_node = make_graph_reasoner_node(None)
