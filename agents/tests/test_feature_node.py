"""Feature-node tests — domain-aware summarization of the enriched event."""
from __future__ import annotations

import json

import pytest

from deliberation.nodes.feature import feature_node


class TestFeatureNodeFraud:
    def test_fraud_event_summarized(self, fraud_event_json: str) -> None:
        result = feature_node({
            "event_id": "evt-fraud-1",
            "domain": "fraud",
            "baseline_entity_id": "cardholder-9",
            "graph_entity_ids": [],
            "enriched_event_json": fraud_event_json,
        })

        summary = result["feature_summary"]
        assert summary.event_id == "evt-fraud-1"
        assert summary.domain == "fraud"
        assert "cardholder-9" in summary.headline
        assert summary.numeric["amount_minor"] == 12500.0
        assert summary.numeric["cardholder_velocity"] == 17.0
        assert summary.numeric["bin_risk_score"] == pytest.approx(0.68)
        # Geo split appears as a bullet because billing != shipping.
        joined_bullets = " ".join(summary.bullets)
        assert "billing=US" in joined_bullets and "shipping=DE" in joined_bullets

    def test_fraud_event_no_geo_split_bullet_when_countries_match(self) -> None:
        evt = {
            "eventId": "e",
            "cardholderId": "c", "amountMinor": 100, "currency": "USD",
            "merchantId": "m", "bin": "4", "deviceFingerprint": "d", "ipAddress": "i",
            "billingCountry": "US", "shippingCountry": "US",
            "cardPresent": True, "channel": "WEB",
            "cardholderVelocity": 1, "binRiskScore": 0.1,
        }
        result = feature_node({
            "event_id": "e", "domain": "fraud",
            "baseline_entity_id": "c", "graph_entity_ids": [],
            "enriched_event_json": json.dumps(evt),
        })
        joined = " ".join(result["feature_summary"].bullets)
        assert "Geographic split" not in joined


class TestFeatureNodeSecurity:
    def test_security_event_summarized(self, security_event_json: str) -> None:
        result = feature_node({
            "event_id": "evt-sec-1",
            "domain": "security",
            "baseline_entity_id": "alice@corp",
            "graph_entity_ids": [],
            "enriched_event_json": security_event_json,
        })

        summary = result["feature_summary"]
        assert summary.domain == "security"
        assert "alice@corp" in summary.headline
        assert summary.numeric["principal_velocity"] == 9.0
        assert summary.numeric["failed_logins_recent"] == 3.0
        assert summary.numeric["is_privileged"] == 1.0
        joined = " ".join(summary.bullets)
        assert "prod-db-1" in joined  # target_resource bullet

    def test_non_privileged_no_marker(self) -> None:
        evt = {
            "eventId": "e", "principalId": "u", "hostId": "h", "sourceIp": "i",
            "authMethod": "PASSWORD", "result": "SUCCESS",
            "isPrivileged": False,
            "principalVelocity": 1, "failedLoginsRecent": 0,
        }
        result = feature_node({
            "event_id": "e", "domain": "security",
            "baseline_entity_id": "u", "graph_entity_ids": [],
            "enriched_event_json": json.dumps(evt),
        })
        assert result["feature_summary"].numeric["is_privileged"] == 0.0


class TestFeatureNodeErrors:
    def test_malformed_json_emits_error(self) -> None:
        result = feature_node({
            "event_id": "evt-bad",
            "domain": "fraud",
            "baseline_entity_id": "x",
            "graph_entity_ids": [],
            "enriched_event_json": "{not json",
        })
        assert "errors" in result
        assert "feature_node:" in result["errors"][0]
        # Still emits a summary, just degraded.
        assert result["feature_summary"].event_id == "evt-bad"
        assert "Malformed event payload" in result["feature_summary"].headline

    def test_unknown_domain_fallback(self) -> None:
        result = feature_node({
            "event_id": "e", "domain": "unknown",
            "baseline_entity_id": "x", "graph_entity_ids": [],
            "enriched_event_json": json.dumps({"foo": "bar"}),
        })
        # No exception; just a minimal summary.
        assert result["feature_summary"].domain == "unknown"
        assert "unrecognized domain" in result["feature_summary"].headline
