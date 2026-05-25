"""Feature node — summarizes the enriched event for downstream agents.

Pure logic, no I/O. Domain-aware: fraud and security have different
field sets in the enriched event (see ``configs/{fraud,security}/
enriched-schema.avsc``). The judge prompt embeds the resulting
``FeatureSummary`` verbatim.

Why this exists at all when the raw event JSON is already in state:
LangGraph traces are easier to read when each agent has a structured
output. It also gives us a single place to derive numeric features the
judge can quote in contributing_factors.
"""
from __future__ import annotations

import json
import logging
from typing import Any

from deliberation.state import DeliberationState, FeatureSummary

_LOG = logging.getLogger(__name__)


def feature_node(state: DeliberationState) -> dict[str, Any]:
    """Parse + summarize the enriched event JSON."""
    domain = state["domain"]
    event_id = state["event_id"]
    raw_json = state["enriched_event_json"]

    try:
        event = json.loads(raw_json) if raw_json else {}
    except json.JSONDecodeError as exc:
        _LOG.warning("Could not parse enriched_event_json for %s: %s", event_id, exc)
        return {
            "feature_summary": FeatureSummary(
                event_id=event_id,
                domain=domain,
                headline=f"Malformed event payload for {event_id}",
                bullets=(f"JSON parse error: {exc}",),
                numeric={},
            ),
            "errors": [f"feature_node: {exc}"],
        }

    if domain == "fraud":
        summary = _summarize_fraud(event_id, event)
    elif domain == "security":
        summary = _summarize_security(event_id, event)
    else:
        # Unknown domain — emit a minimal summary; the judge will see the raw
        # event in its prompt and can still reason.
        summary = FeatureSummary(
            event_id=event_id,
            domain=domain,
            headline=f"Event {event_id} (unrecognized domain '{domain}')",
            bullets=(f"Raw fields: {sorted(event.keys())}",),
            numeric={},
        )

    return {"feature_summary": summary}


def _summarize_fraud(event_id: str, event: dict[str, Any]) -> FeatureSummary:
    cardholder = str(event.get("cardholderId", "?"))
    amount_minor = int(event.get("amountMinor", 0))
    currency = str(event.get("currency", "?"))
    merchant = str(event.get("merchantId", "?"))
    velocity = int(event.get("cardholderVelocity", 0))
    bin_risk = float(event.get("binRiskScore", 0.0))
    bin_value = str(event.get("bin", "?"))
    card_present = bool(event.get("cardPresent", False))
    channel = str(event.get("channel", "?"))
    billing = str(event.get("billingCountry", "?"))
    shipping = event.get("shippingCountry")  # may be None

    presence = "card-present" if card_present else "card-not-present"
    bullets = [
        f"Cardholder {cardholder} attempted {amount_minor/100:.2f} {currency} "
        f"at merchant {merchant}.",
        f"BIN {bin_value} → risk score {bin_risk:.2f}. "
        f"Channel: {channel} ({presence}).",
        f"Velocity to date for cardholder: {velocity} events.",
    ]
    if shipping is not None and shipping != billing:
        bullets.append(f"Geographic split: billing={billing}, shipping={shipping}.")

    return FeatureSummary(
        event_id=event_id,
        domain="fraud",
        headline=(
            f"Payment event {event_id}: cardholder={cardholder}, "
            f"amount={amount_minor/100:.2f} {currency}, merchant={merchant}."
        ),
        bullets=tuple(bullets),
        numeric={
            "amount_minor": float(amount_minor),
            "cardholder_velocity": float(velocity),
            "bin_risk_score": bin_risk,
        },
    )


def _summarize_security(event_id: str, event: dict[str, Any]) -> FeatureSummary:
    principal = str(event.get("principalId", "?"))
    host = str(event.get("hostId", "?"))
    method = str(event.get("authMethod", "?"))
    result = str(event.get("result", "?"))
    velocity = int(event.get("principalVelocity", 0))
    failed = int(event.get("failedLoginsRecent", 0))
    privileged = bool(event.get("isPrivileged", False))
    target = event.get("targetResource")
    source_ip = str(event.get("sourceIp", "?"))

    priv_marker = "(privileged) " if privileged else ""
    bullets = [
        f"Principal {principal} {priv_marker}hit host {host} via {method}; "
        f"result={result}.",
        f"Source IP: {source_ip}.",
        f"Cumulative events for principal: {velocity}; "
        f"cumulative non-success: {failed}.",
    ]
    if target:
        bullets.append(f"Target resource: {target}.")

    return FeatureSummary(
        event_id=event_id,
        domain="security",
        headline=(
            f"Auth event {event_id}: principal={principal}, host={host}, "
            f"method={method}, result={result}."
        ),
        bullets=tuple(bullets),
        numeric={
            "principal_velocity": float(velocity),
            "failed_logins_recent": float(failed),
            "is_privileged": 1.0 if privileged else 0.0,
        },
    )
