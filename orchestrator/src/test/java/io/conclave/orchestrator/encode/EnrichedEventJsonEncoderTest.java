package io.conclave.orchestrator.encode;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conclave.events.fraud.EnrichedPaymentEvent;
import io.conclave.events.security.EnrichedAuthEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnrichedEventJsonEncoderTest {

    private final EnrichedEventJsonEncoder encoder = new EnrichedEventJsonEncoder();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void encodes_enriched_payment_event_with_all_fields() throws Exception {
        EnrichedPaymentEvent event = EnrichedPaymentEvent.newBuilder()
                .setEventId("evt-fraud-1")
                .setTimestamp(Instant.ofEpochMilli(1716624000000L))
                .setCardholderId("cardholder-9")
                .setCardToken("tok_test")
                .setAmountMinor(12500L)
                .setCurrency("USD")
                .setMerchantId("mer-1")
                .setMerchantCategoryCode(5732)
                .setBin("424242")
                .setDeviceFingerprint("dev-suspect")
                .setIpAddress("203.0.113.5")
                .setBillingCountry("US")
                .setShippingCountry("DE")
                .setCardPresent(false)
                .setChannel("WEB")
                .setCardholderVelocity(17L)
                .setBinRiskScore(0.68)
                .setBaselineEntityId("cardholder-9")
                .setGraphEntityIds(List.of("cardholder-9", "dev-suspect", "203.0.113.5", "mer-1"))
                .setFeatureExtractedAt(Instant.ofEpochMilli(1716624000050L))
                .build();

        JsonNode json = mapper.readTree(encoder.encode(event));

        // Field names match the Avro schema (camelCase) — same shape the M5
        // Python feature_node parses from agents/tests/conftest.py.
        assertThat(json.get("eventId").asText()).isEqualTo("evt-fraud-1");
        assertThat(json.get("amountMinor").asLong()).isEqualTo(12500L);
        assertThat(json.get("currency").asText()).isEqualTo("USD");
        // Timestamps come out as epoch millis, not ISO-8601 — matches the
        // fixture expectations on the M5 side.
        assertThat(json.get("timestamp").asLong()).isEqualTo(1716624000000L);
        assertThat(json.get("featureExtractedAt").asLong()).isEqualTo(1716624000050L);
        // Optional union fields flatten — no {"string": "DE"} wrapper.
        assertThat(json.get("shippingCountry").asText()).isEqualTo("DE");
        assertThat(json.get("cardPresent").asBoolean()).isFalse();
        assertThat(json.get("binRiskScore").asDouble()).isEqualTo(0.68);
        // Arrays come through as JSON arrays.
        assertThat(json.get("graphEntityIds").isArray()).isTrue();
        assertThat(json.get("graphEntityIds")).hasSize(4);
        assertThat(json.get("graphEntityIds").get(1).asText()).isEqualTo("dev-suspect");
    }

    @Test
    void null_optional_field_serializes_as_json_null() throws Exception {
        EnrichedPaymentEvent event = EnrichedPaymentEvent.newBuilder()
                .setEventId("evt-x")
                .setTimestamp(Instant.now())
                .setCardholderId("c")
                .setCardToken("t")
                .setAmountMinor(100L)
                .setCurrency("USD")
                .setMerchantId("m")
                .setMerchantCategoryCode(1)
                .setBin("4")
                .setDeviceFingerprint("d")
                .setIpAddress("1.2.3.4")
                .setBillingCountry("US")
                .setShippingCountry(null)  // optional union [null, string]
                .setCardPresent(true)
                .setChannel("WEB")
                .setCardholderVelocity(1L)
                .setBinRiskScore(0.0)
                .setBaselineEntityId("c")
                .setGraphEntityIds(List.of())
                .setFeatureExtractedAt(Instant.now())
                .build();

        JsonNode json = mapper.readTree(encoder.encode(event));
        // Avro union [null, string] with value null serializes as JSON null,
        // not as an Avro-style wrapper. M5 parses `event.get("shippingCountry")`
        // → None which is the documented behavior.
        assertThat(json.get("shippingCountry").isNull()).isTrue();
        assertThat(json.get("graphEntityIds").isArray()).isTrue();
        assertThat(json.get("graphEntityIds")).isEmpty();
    }

    @Test
    void encodes_enriched_auth_event() throws Exception {
        EnrichedAuthEvent event = EnrichedAuthEvent.newBuilder()
                .setEventId("evt-sec-1")
                .setTimestamp(Instant.ofEpochMilli(1716624000000L))
                .setPrincipalId("alice@corp")
                .setHostId("host-prod-12")
                .setSourceIp("10.0.7.42")
                .setAuthMethod("SSH_KEY")
                .setResult("SUCCESS")
                .setTargetResource("prod-db-1")
                .setUserAgent(null)
                .setSessionId("sess-1")
                .setIsPrivileged(true)
                .setPrincipalVelocity(9L)
                .setFailedLoginsRecent(3L)
                .setBaselineEntityId("alice@corp")
                .setGraphEntityIds(List.of("alice@corp", "host-prod-12", "10.0.7.42", "prod-db-1"))
                .setFeatureExtractedAt(Instant.ofEpochMilli(1716624000050L))
                .build();

        JsonNode json = mapper.readTree(encoder.encode(event));
        assertThat(json.get("principalId").asText()).isEqualTo("alice@corp");
        assertThat(json.get("isPrivileged").asBoolean()).isTrue();
        assertThat(json.get("userAgent").isNull()).isTrue();
        assertThat(json.get("targetResource").asText()).isEqualTo("prod-db-1");
        assertThat(json.get("failedLoginsRecent").asLong()).isEqualTo(3L);
    }
}
