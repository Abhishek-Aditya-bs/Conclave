package io.conclave.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.conclave.events.fraud.PaymentEvent;
import io.conclave.events.security.AuthEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Avro schemas defined in {@code configs/fraud/schema.avsc} and
 * {@code configs/security/schema.avsc}. Pure Avro binary encode/decode — no Kafka, no
 * Confluent, no Spring. Fast (&lt;100ms total) and runs in Surefire.
 *
 * <p>These tests are the contract for the schema files: any incompatible change to the
 * schemas (renaming a field, changing a type, removing a default) will fail here loudly.
 */
class AvroRoundTripTest {

    @Test
    @DisplayName("PaymentEvent round-trips through Avro binary encoding with every field preserved")
    void paymentEventRoundTrip() throws IOException {
        PaymentEvent original = EventFactory.randomPaymentEvent();

        PaymentEvent decoded = roundTrip(original, PaymentEvent.class);

        assertThat(decoded.getEventId()).isEqualTo(original.getEventId());
        assertThat(decoded.getTimestamp()).isEqualTo(original.getTimestamp());
        assertThat(decoded.getCardholderId()).isEqualTo(original.getCardholderId());
        assertThat(decoded.getCardToken()).isEqualTo(original.getCardToken());
        assertThat(decoded.getAmountMinor()).isEqualTo(original.getAmountMinor());
        assertThat(decoded.getCurrency()).isEqualTo(original.getCurrency());
        assertThat(decoded.getMerchantId()).isEqualTo(original.getMerchantId());
        assertThat(decoded.getMerchantCategoryCode()).isEqualTo(original.getMerchantCategoryCode());
        assertThat(decoded.getBin()).isEqualTo(original.getBin());
        assertThat(decoded.getDeviceFingerprint()).isEqualTo(original.getDeviceFingerprint());
        assertThat(decoded.getIpAddress()).isEqualTo(original.getIpAddress());
        assertThat(decoded.getBillingCountry()).isEqualTo(original.getBillingCountry());
        assertThat(decoded.getShippingCountry()).isEqualTo(original.getShippingCountry());
        assertThat(decoded.getCardPresent()).isEqualTo(original.getCardPresent());
        assertThat(decoded.getChannel()).isEqualTo(original.getChannel());
    }

    @Test
    @DisplayName("PaymentEvent handles a null optional shippingCountry")
    void paymentEventWithNullShippingCountry() throws IOException {
        PaymentEvent original = PaymentEvent.newBuilder(EventFactory.randomPaymentEvent())
                .setShippingCountry(null)
                .build();

        PaymentEvent decoded = roundTrip(original, PaymentEvent.class);

        assertThat(decoded.getShippingCountry()).isNull();
        assertThat(decoded.getEventId()).isEqualTo(original.getEventId());
    }

    @Test
    @DisplayName("AuthEvent round-trips through Avro binary encoding with every field preserved")
    void authEventRoundTrip() throws IOException {
        AuthEvent original = EventFactory.randomAuthEvent();

        AuthEvent decoded = roundTrip(original, AuthEvent.class);

        assertThat(decoded.getEventId()).isEqualTo(original.getEventId());
        assertThat(decoded.getTimestamp()).isEqualTo(original.getTimestamp());
        assertThat(decoded.getPrincipalId()).isEqualTo(original.getPrincipalId());
        assertThat(decoded.getHostId()).isEqualTo(original.getHostId());
        assertThat(decoded.getSourceIp()).isEqualTo(original.getSourceIp());
        assertThat(decoded.getAuthMethod()).isEqualTo(original.getAuthMethod());
        assertThat(decoded.getResult()).isEqualTo(original.getResult());
        assertThat(decoded.getTargetResource()).isEqualTo(original.getTargetResource());
        assertThat(decoded.getUserAgent()).isEqualTo(original.getUserAgent());
        assertThat(decoded.getSessionId()).isEqualTo(original.getSessionId());
        assertThat(decoded.getIsPrivileged()).isEqualTo(original.getIsPrivileged());
    }

    @Test
    @DisplayName("AuthEvent handles null optional fields (targetResource, userAgent, sessionId)")
    void authEventWithNullOptionals() throws IOException {
        AuthEvent original = AuthEvent.newBuilder(EventFactory.randomAuthEvent())
                .setTargetResource(null)
                .setUserAgent(null)
                .setSessionId(null)
                .build();

        AuthEvent decoded = roundTrip(original, AuthEvent.class);

        assertThat(decoded.getTargetResource()).isNull();
        assertThat(decoded.getUserAgent()).isNull();
        assertThat(decoded.getSessionId()).isNull();
    }

    @Test
    @DisplayName("Avro builder rejects a PaymentEvent missing a required field (eventId)")
    void paymentEventBuilderRejectsMissingRequiredField() {
        assertThatThrownBy(() -> PaymentEvent.newBuilder()
                .setTimestamp(java.time.Instant.EPOCH)
                .setCardholderId("c")
                .setCardToken("t")
                .setAmountMinor(1L)
                .setCurrency("USD")
                .setMerchantId("m")
                .setMerchantCategoryCode(5411)
                .setBin("424242")
                .setDeviceFingerprint("d")
                .setIpAddress("1.1.1.1")
                .setBillingCountry("US")
                .setCardPresent(false)
                .setChannel(io.conclave.events.fraud.Channel.WEB)
                .build())
                .isInstanceOf(org.apache.avro.AvroRuntimeException.class)
                .hasMessageContaining("eventId");
    }

    /** Encode then decode an Avro {@link org.apache.avro.specific.SpecificRecord} to itself. */
    private static <T extends org.apache.avro.specific.SpecificRecord> T roundTrip(T value, Class<T> type)
            throws IOException {
        SpecificDatumWriter<T> writer = new SpecificDatumWriter<>(type);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
        writer.write(value, encoder);
        encoder.flush();

        SpecificDatumReader<T> reader = new SpecificDatumReader<>(type);
        BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(out.toByteArray(), null);
        return reader.read(null, decoder);
    }
}
