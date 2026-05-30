"""Shared pytest fixtures for the deliberation service."""
from __future__ import annotations

import json

import pytest

from deliberation.state import (
    BaselineFinding,
    ContributingFactor,
    Decision,
    FeatureSummary,
    GraphFinding,
    VerdictLabel,
)


@pytest.fixture
def fraud_event_json() -> str:
    """A canonical fraud event payload — matches the EnrichedPaymentEvent shape."""
    return json.dumps(
        {
            "eventId": "evt-fraud-1",
            "timestamp": 1716624000000,
            "cardholderId": "cardholder-9",
            "cardToken": "tok_xxxx",
            "amountMinor": 12500,
            "currency": "USD",
            "merchantId": "mer-1",
            "merchantCategoryCode": 5732,
            "bin": "424242",
            "deviceFingerprint": "dev-suspect",
            "ipAddress": "203.0.113.5",
            "billingCountry": "US",
            "shippingCountry": "DE",
            "cardPresent": False,
            "channel": "WEB",
            "cardholderVelocity": 17,
            "binRiskScore": 0.68,
            "baselineEntityId": "cardholder-9",
            "graphEntityIds": ["cardholder-9", "dev-suspect", "203.0.113.5", "mer-1"],
            "featureExtractedAt": 1716624000050,
        }
    )


@pytest.fixture
def security_event_json() -> str:
    """A canonical security event payload — matches the EnrichedAuthEvent shape."""
    return json.dumps(
        {
            "eventId": "evt-sec-1",
            "timestamp": 1716624000000,
            "principalId": "alice@corp",
            "hostId": "host-prod-12",
            "sourceIp": "10.0.7.42",
            "authMethod": "SSH_KEY",
            "result": "SUCCESS",
            "targetResource": "prod-db-1",
            "userAgent": None,
            "sessionId": "sess-1",
            "isPrivileged": True,
            "principalVelocity": 9,
            "failedLoginsRecent": 3,
            "baselineEntityId": "alice@corp",
            "graphEntityIds": ["alice@corp", "host-prod-12", "10.0.7.42", "prod-db-1"],
            "featureExtractedAt": 1716624000050,
        }
    )


@pytest.fixture
def sample_feature_summary() -> FeatureSummary:
    return FeatureSummary(
        event_id="evt-1",
        domain="fraud",
        headline="Sample event",
        bullets=("Bullet one.", "Bullet two."),
        numeric={"amount_minor": 12500.0},
    )


@pytest.fixture
def sample_baseline_finding() -> BaselineFinding:
    return BaselineFinding(
        entity_id="cardholder-9",
        domain="fraud",
        is_cold_start=False,
        event_count=42,
        embedding_dim=384,
        last_updated_epoch_ms=1716624000000,
    )


@pytest.fixture
def sample_graph_finding() -> GraphFinding:
    return GraphFinding(
        template_name="fraud_card_testing_ring",
        root_entity_id="dev-suspect",
        domain="fraud",
        attributes={"cardholder_count": 7, "card_count": 12},
        risk_signal=0.82,
        query_latency_ms=4,
    )


@pytest.fixture
def sample_decision() -> Decision:
    return Decision(
        score=0.83,
        verdict_label=VerdictLabel.BLOCK,
        verdict_explanation_md="**Block.** Device fans out to many cardholders.",
        contributing_factors=(
            ContributingFactor(
                name="graph_ring_detected",
                weight=0.8,
                evidence="Device touched 7 cardholders in last 24h.",
            ),
        ),
        latency_ms=240,
        judge_provider="anthropic",
        judge_model="claude-haiku-4-5-20251001",
    )
