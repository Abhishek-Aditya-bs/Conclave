package io.conclave.orchestrator.encode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.conclave.deliberation.proto.DeliberationRequest;
import io.conclave.events.fraud.EnrichedPaymentEvent;
import io.conclave.events.security.EnrichedAuthEvent;
import io.conclave.ingest.EventDomain;
import java.time.Instant;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

class DeliberationRequestTranslatorTest {

    private final DeliberationRequestTranslator translator =
            new DeliberationRequestTranslator(new EnrichedEventJsonEncoder());

    @Test
    void fraud_event_translates_with_all_orchestration_fields() {
        EnrichedPaymentEvent event = EnrichedPaymentEvent.newBuilder()
                .setEventId("evt-fraud-1")
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
                .setShippingCountry(null)
                .setCardPresent(true)
                .setChannel("WEB")
                .setCardholderVelocity(1L)
                .setBinRiskScore(0.0)
                .setBaselineEntityId("c")
                .setGraphEntityIds(List.of("c", "d", "1.2.3.4", "m"))
                .setFeatureExtractedAt(Instant.now())
                .build();

        DeliberationRequest req = translator.toRequest(EventDomain.FRAUD, event);

        assertThat(req.getEventId()).isEqualTo("evt-fraud-1");
        assertThat(req.getDomain()).isEqualTo("fraud");
        assertThat(req.getBaselineEntityId()).isEqualTo("c");
        assertThat(req.getGraphEntityIdsList()).containsExactly("c", "d", "1.2.3.4", "m");
        // JSON contains all source fields.
        assertThat(req.getEnrichedEventJson())
                .contains("\"eventId\":\"evt-fraud-1\"")
                .contains("\"channel\":\"WEB\"");
    }

    @Test
    void security_event_translates_with_security_domain() {
        EnrichedAuthEvent event = EnrichedAuthEvent.newBuilder()
                .setEventId("evt-sec-1")
                .setTimestamp(Instant.now())
                .setPrincipalId("alice@corp")
                .setHostId("h")
                .setSourceIp("10.0.0.1")
                .setAuthMethod("PASSWORD")
                .setResult("SUCCESS")
                .setIsPrivileged(false)
                .setPrincipalVelocity(1L)
                .setFailedLoginsRecent(0L)
                .setBaselineEntityId("alice@corp")
                .setGraphEntityIds(List.of("alice@corp", "h"))
                .setFeatureExtractedAt(Instant.now())
                .build();

        DeliberationRequest req = translator.toRequest(EventDomain.SECURITY, event);

        assertThat(req.getDomain()).isEqualTo("security");
        assertThat(req.getBaselineEntityId()).isEqualTo("alice@corp");
        assertThat(req.getGraphEntityIdsList()).containsExactly("alice@corp", "h");
    }

    @Test
    void missing_event_id_field_fails_fast() {
        // Construct an Avro record whose schema has no eventId field.
        Schema schema = Schema.createRecord("Bogus", null, "test", false, List.of(
                new Schema.Field("baselineEntityId", Schema.create(Schema.Type.STRING), null, null),
                new Schema.Field("graphEntityIds",
                        Schema.createArray(Schema.create(Schema.Type.STRING)),
                        null, null)
        ));
        GenericRecord record = new GenericData.Record(schema);
        record.put("baselineEntityId", "x");
        record.put("graphEntityIds", List.of("x"));

        assertThatThrownBy(() -> translator.toRequest(EventDomain.FRAUD, record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void null_baseline_entity_id_fails_fast() {
        Schema schema = Schema.createRecord("Bogus", null, "test", false, List.of(
                new Schema.Field("eventId", Schema.create(Schema.Type.STRING), null, null),
                new Schema.Field("baselineEntityId",
                        Schema.createUnion(Schema.create(Schema.Type.NULL),
                                Schema.create(Schema.Type.STRING)),
                        null, Schema.Field.NULL_DEFAULT_VALUE),
                new Schema.Field("graphEntityIds",
                        Schema.createArray(Schema.create(Schema.Type.STRING)),
                        null, null)
        ));
        GenericRecord record = new GenericData.Record(schema);
        record.put("eventId", "e");
        record.put("baselineEntityId", null);
        record.put("graphEntityIds", List.of());

        assertThatThrownBy(() -> translator.toRequest(EventDomain.FRAUD, record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baselineEntityId");
    }

    @Test
    void empty_graph_entity_ids_list_is_allowed() {
        EnrichedPaymentEvent event = EnrichedPaymentEvent.newBuilder()
                .setEventId("e")
                .setTimestamp(Instant.now())
                .setCardholderId("c").setCardToken("t").setAmountMinor(1L)
                .setCurrency("USD").setMerchantId("m").setMerchantCategoryCode(1)
                .setBin("4").setDeviceFingerprint("d").setIpAddress("i")
                .setBillingCountry("US").setShippingCountry(null)
                .setCardPresent(true).setChannel("WEB")
                .setCardholderVelocity(0L).setBinRiskScore(0.0)
                .setBaselineEntityId("c")
                .setGraphEntityIds(List.of())
                .setFeatureExtractedAt(Instant.now())
                .build();

        DeliberationRequest req = translator.toRequest(EventDomain.FRAUD, event);
        assertThat(req.getGraphEntityIdsList()).isEmpty();
    }
}
