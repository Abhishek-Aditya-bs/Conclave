package io.conclave.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.conclave.events.fraud.Channel;
import io.conclave.events.fraud.EnrichedPaymentEvent;
import io.conclave.events.fraud.PaymentEvent;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.test.TestRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FraudFeatureSpec} using Kafka's {@link TopologyTestDriver}.
 * In-process, no broker, no Docker — runs in Surefire and completes in &lt;1s.
 */
class FraudFeatureSpecTest {

    private static final String REGISTRY = "mock://m2-fraud-unit-" + UUID.randomUUID();

    private FraudFeatureSpec spec;
    private TopologyTestDriver driver;
    private TestInputTopic<String, PaymentEvent> in;
    private TestOutputTopic<String, EnrichedPaymentEvent> out;

    @BeforeEach
    void setUp() throws Exception {
        spec = new FraudFeatureSpec(REGISTRY);
        Topology topology = FeatureExtractionTopology.build(spec);
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "fraud-unit-" + UUID.randomUUID());
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
        props.put(StreamsConfig.STATE_DIR_CONFIG, Files.createTempDirectory("conclave-state").toString());
        driver = new TopologyTestDriver(topology, props);
        in = driver.createInputTopic(spec.inputTopic(),
                Serdes.String().serializer(), spec.rawSerde().serializer());
        out = driver.createOutputTopic(spec.outputTopic(),
                Serdes.String().deserializer(), spec.enrichedSerde().deserializer());
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.close();
        }
    }

    @Test
    @DisplayName("cardholderVelocity increments per cardholderId and resets independently per cardholder")
    void velocityIncrementsPerCardholder() {
        PaymentEvent a1 = paymentForCardholder("cust_A");
        PaymentEvent a2 = paymentForCardholder("cust_A");
        PaymentEvent b1 = paymentForCardholder("cust_B");
        PaymentEvent a3 = paymentForCardholder("cust_A");

        in.pipeInput(a1.getEventId(), a1);
        in.pipeInput(a2.getEventId(), a2);
        in.pipeInput(b1.getEventId(), b1);
        in.pipeInput(a3.getEventId(), a3);

        List<TestRecord<String, EnrichedPaymentEvent>> records = out.readRecordsToList();
        assertThat(records).hasSize(4);

        assertThat(findByEventId(records, a1.getEventId()).getCardholderVelocity()).isEqualTo(1L);
        assertThat(findByEventId(records, a2.getEventId()).getCardholderVelocity()).isEqualTo(2L);
        assertThat(findByEventId(records, b1.getEventId()).getCardholderVelocity()).isEqualTo(1L);
        assertThat(findByEventId(records, a3.getEventId()).getCardholderVelocity()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Enriched output preserves every raw field and keys by eventId")
    void preservesAllRawFields() {
        PaymentEvent raw = paymentForCardholder("cust_X");

        in.pipeInput(raw.getEventId(), raw);

        TestRecord<String, EnrichedPaymentEvent> rec = out.readRecord();
        assertThat(rec.getKey()).isEqualTo(raw.getEventId());
        EnrichedPaymentEvent e = rec.getValue();
        assertThat(e.getEventId()).isEqualTo(raw.getEventId());
        assertThat(e.getTimestamp()).isEqualTo(raw.getTimestamp());
        assertThat(e.getCardholderId()).isEqualTo(raw.getCardholderId());
        assertThat(e.getCardToken()).isEqualTo(raw.getCardToken());
        assertThat(e.getAmountMinor()).isEqualTo(raw.getAmountMinor());
        assertThat(e.getCurrency()).isEqualTo(raw.getCurrency());
        assertThat(e.getMerchantId()).isEqualTo(raw.getMerchantId());
        assertThat(e.getMerchantCategoryCode()).isEqualTo(raw.getMerchantCategoryCode());
        assertThat(e.getBin()).isEqualTo(raw.getBin());
        assertThat(e.getDeviceFingerprint()).isEqualTo(raw.getDeviceFingerprint());
        assertThat(e.getIpAddress()).isEqualTo(raw.getIpAddress());
        assertThat(e.getBillingCountry()).isEqualTo(raw.getBillingCountry());
        assertThat(e.getShippingCountry()).isEqualTo(raw.getShippingCountry());
        assertThat(e.getCardPresent()).isEqualTo(raw.getCardPresent());
        assertThat(e.getChannel()).isEqualTo(raw.getChannel().name());
    }

    @Test
    @DisplayName("Computed feature fields are populated correctly")
    void computesFeatureFields() {
        PaymentEvent raw = paymentForCardholder("cust_Y");

        in.pipeInput(raw.getEventId(), raw);

        EnrichedPaymentEvent e = out.readRecord().getValue();
        assertThat(e.getBaselineEntityId()).isEqualTo(raw.getCardholderId());
        assertThat(e.getGraphEntityIds()).containsExactly(
                raw.getCardholderId(),
                raw.getDeviceFingerprint(),
                raw.getIpAddress(),
                raw.getMerchantId());
        assertThat(e.getBinRiskScore()).isBetween(0.0, 1.0);
        assertThat(e.getFeatureExtractedAt()).isAfter(Instant.EPOCH);
    }

    @Test
    @DisplayName("binRiskScore is deterministic for the same BIN across calls")
    void binRiskScoreIsDeterministic() {
        assertThat(FraudFeatureSpec.computeBinRiskScore("424242"))
                .isEqualTo(FraudFeatureSpec.computeBinRiskScore("424242"));
        assertThat(FraudFeatureSpec.computeBinRiskScore("411111"))
                .isNotEqualTo(FraudFeatureSpec.computeBinRiskScore("424242"));
    }

    @Test
    @DisplayName("Pumping 100 events through the topology produces 100 enriched events")
    void pump100Events() {
        for (int i = 0; i < 100; i++) {
            PaymentEvent e = paymentForCardholder("cust_" + (i % 10));
            in.pipeInput(e.getEventId(), e);
        }
        List<TestRecord<String, EnrichedPaymentEvent>> records = out.readRecordsToList();
        assertThat(records).hasSize(100);
        // Every cardholder seen 10 times → max velocity per cardholder = 10
        for (TestRecord<String, EnrichedPaymentEvent> r : records) {
            assertThat(r.getValue().getCardholderVelocity()).isBetween(1L, 10L);
        }
    }

    @Test
    @DisplayName("FraudFeatureSpec exposes the correct domain wiring")
    void domainWiring() {
        assertThat(spec.domain().key()).isEqualTo("fraud");
        assertThat(spec.inputTopic()).isEqualTo("events.fraud.raw");
        assertThat(spec.outputTopic()).isEqualTo("events.fraud.enriched");
        assertThat(spec.rawType()).isEqualTo(PaymentEvent.class);
        assertThat(spec.enrichedType()).isEqualTo(EnrichedPaymentEvent.class);
        assertThat(spec.rawSerde()).isNotNull();
        assertThat(spec.enrichedSerde()).isNotNull();
    }

    private static PaymentEvent paymentForCardholder(String cardholderId) {
        return PaymentEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTimestamp(Instant.now())
                .setCardholderId(cardholderId)
                .setCardToken("tok_" + UUID.randomUUID())
                .setAmountMinor(1234L)
                .setCurrency("USD")
                .setMerchantId("merch_acme")
                .setMerchantCategoryCode(5411)
                .setBin("424242")
                .setDeviceFingerprint("dev_xyz")
                .setIpAddress("10.0.0.1")
                .setBillingCountry("US")
                .setShippingCountry("US")
                .setCardPresent(false)
                .setChannel(Channel.WEB)
                .build();
    }

    private static EnrichedPaymentEvent findByEventId(
            List<TestRecord<String, EnrichedPaymentEvent>> records, String eventId) {
        for (TestRecord<String, EnrichedPaymentEvent> r : records) {
            if (r.getKey().equals(eventId)) return r.getValue();
        }
        throw new AssertionError("no record with eventId=" + eventId + " among "
                + records.stream().map(TestRecord::getKey).toList());
    }
}
