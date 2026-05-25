package io.conclave.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conclave.deliberation.proto.ContributingFactor;
import io.conclave.deliberation.proto.Decision;
import io.conclave.deliberation.proto.DeliberationRequest;
import io.conclave.deliberation.proto.DeliberationResponse;
import io.conclave.deliberation.proto.DeliberationServiceGrpc;
import io.conclave.events.fraud.EnrichedPaymentEvent;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end IT for M6's happy path under the fraud profile:
 * <pre>
 *   produce EnrichedPaymentEvent on events.fraud.enriched
 *     → orchestrator consumes
 *     → calls our in-process mock M5 over real Netty gRPC
 *     → persists to Postgres (Testcontainers)
 *     → emits JSON on decisions.fraud
 * </pre>
 *
 * <p>Uses single Kafka + Postgres containers reused across the class.
 * The mock M5 is a real Netty gRPC server bound to a free loopback port
 * so the production transport (grpc-netty-shaded) runs end to end.
 *
 * <p>M2's Kafka Streams topology also boots under the fraud profile —
 * it reads from {@code events.fraud.raw} (which we never publish to in
 * this IT) and writes to {@code events.fraud.enriched}, so it sits idle
 * while we publish enriched events directly. State-dir is per-class so
 * separate test classes don't collide.
 */
@SpringBootTest(properties = {
        "conclave.orchestrator.deliberation-deadline-ms=5000",
        "spring.kafka.consumer.auto-offset-reset=earliest",
})
@ActiveProfiles("fraud")
@Testcontainers
class DecisionOrchestratorIT {

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    static SpecificMockServer mockM5;
    static int mockM5Port;

    @BeforeAll
    static void startMockM5() throws Exception {
        mockM5Port = pickFreePort();
        mockM5 = new SpecificMockServer(mockM5Port);
        mockM5.start();
    }

    @AfterAll
    static void stopMockM5() {
        if (mockM5 != null) mockM5.stop();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("conclave.orchestrator.deliberation-target",
                () -> "localhost:" + mockM5Port);
        registry.add("spring.kafka.streams.state-dir",
                () -> "./target/it-state/" + System.nanoTime());
    }

    @Autowired KafkaTemplate<String, org.apache.avro.specific.SpecificRecord> avroTemplate;
    @Autowired JdbcTemplate jdbc;

    @Test
    void happy_path_persists_and_emits_decision() throws Exception {
        mockM5.nextResponse(buildBlockingResponse());

        EnrichedPaymentEvent event = sampleEvent("evt-it-1");
        avroTemplate.send("events.fraud.enriched", event.getEventId().toString(), event)
                .get();

        // 1. Postgres row appears.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM decisions WHERE event_id = ?",
                    Integer.class, "evt-it-1");
            assertThat(count).isEqualTo(1);
        });

        var row = jdbc.queryForMap(
                "SELECT score, verdict_label, judge_provider, judge_model, "
                        + "       contributing_factors::text AS factors_json "
                        + "  FROM decisions WHERE event_id = ?", "evt-it-1");
        assertThat(row.get("score")).isEqualTo(0.83);
        assertThat(row.get("verdict_label")).isEqualTo("BLOCK");
        assertThat(row.get("judge_provider")).isEqualTo("anthropic");
        assertThat(row.get("judge_model")).isEqualTo("claude-haiku-4-5-20251001");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode factors = mapper.readTree((String) row.get("factors_json"));
        assertThat(factors.isArray()).isTrue();
        assertThat(factors.get(0).get("name").asText()).isEqualTo("graph_ring_detected");

        // 2. decisions.fraud carries the JSON.
        try (KafkaConsumer<String, String> consumer = stringConsumer("decisions-fraud-test")) {
            consumer.subscribe(List.of("decisions.fraud"));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(15));
            assertThat(records.count()).isGreaterThan(0);
            ConsumerRecord<String, String> rec = records.iterator().next();
            assertThat(rec.key()).isEqualTo("evt-it-1");
            JsonNode payload = mapper.readTree(rec.value());
            assertThat(payload.get("event_id").asText()).isEqualTo("evt-it-1");
            assertThat(payload.get("verdict_label").asText()).isEqualTo("BLOCK");
            assertThat(payload.get("contributing_factors").isArray()).isTrue();
        }

        // 3. The orchestrator forwarded the right request to M5.
        DeliberationRequest received = mockM5.lastRequest();
        assertThat(received).isNotNull();
        assertThat(received.getEventId()).isEqualTo("evt-it-1");
        assertThat(received.getDomain()).isEqualTo("fraud");
        assertThat(received.getBaselineEntityId()).isEqualTo("cardholder-1");
        assertThat(received.getEnrichedEventJson()).contains("\"eventId\":\"evt-it-1\"");
    }

    @Test
    void m5_unavailable_routes_event_to_dlq() throws Exception {
        mockM5.nextError(io.grpc.Status.UNAVAILABLE.withDescription("m5 down"));

        EnrichedPaymentEvent event = sampleEvent("evt-it-fail");
        avroTemplate.send("events.fraud.enriched", event.getEventId().toString(), event)
                .get();

        // DLQ topic carries the failure payload; no row in Postgres.
        try (KafkaConsumer<String, String> consumer = stringConsumer("dlq-test")) {
            consumer.subscribe(List.of("decisions.fraud.failed"));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(20));
            assertThat(records.count()).isGreaterThan(0);
            JsonNode payload = new ObjectMapper().readTree(records.iterator().next().value());
            assertThat(payload.get("event_id").asText()).isEqualTo("evt-it-fail");
            assertThat(payload.get("reason").asText()).isEqualTo("m5_unavailable");
            assertThat(payload.get("enriched_event_json").asText())
                    .contains("evt-it-fail");
        }

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM decisions WHERE event_id = ?",
                Integer.class, "evt-it-fail");
        assertThat(count).isZero();
    }

    // -------------------- helpers --------------------

    private static EnrichedPaymentEvent sampleEvent(String eventId) {
        return EnrichedPaymentEvent.newBuilder()
                .setEventId(eventId)
                .setTimestamp(Instant.now())
                .setCardholderId("cardholder-1")
                .setCardToken("tok").setAmountMinor(12500L).setCurrency("USD")
                .setMerchantId("mer-1").setMerchantCategoryCode(5732)
                .setBin("424242").setDeviceFingerprint("dev-1").setIpAddress("1.2.3.4")
                .setBillingCountry("US").setShippingCountry(null)
                .setCardPresent(false).setChannel("WEB")
                .setCardholderVelocity(5L).setBinRiskScore(0.42)
                .setBaselineEntityId("cardholder-1")
                .setGraphEntityIds(List.of("cardholder-1", "dev-1", "1.2.3.4", "mer-1"))
                .setFeatureExtractedAt(Instant.now())
                .build();
    }

    private static DeliberationResponse buildBlockingResponse() {
        return DeliberationResponse.newBuilder()
                .setDecision(Decision.newBuilder()
                        .setScore(0.83)
                        .setVerdictLabel("BLOCK")
                        .setVerdictExplanationMd("Block — IT-fixture verdict.")
                        .addContributingFactors(ContributingFactor.newBuilder()
                                .setName("graph_ring_detected")
                                .setWeight(0.8)
                                .setEvidence("seven cardholders share device"))
                        .setLatencyMs(180L)
                        .setJudgeProvider("anthropic")
                        .setJudgeModel("claude-haiku-4-5-20251001"))
                .build();
    }

    private KafkaConsumer<String, String> stringConsumer(String groupSuffix) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupSuffix + "-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }

    private static int pickFreePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Scripted M5 servicer; one-shot response or error per test. */
    static final class SpecificMockServer
            extends DeliberationServiceGrpc.DeliberationServiceImplBase {
        private final Server server;
        private volatile DeliberationResponse pendingResponse;
        private volatile io.grpc.Status pendingError;
        private volatile DeliberationRequest lastRequest;

        SpecificMockServer(int port) {
            server = NettyServerBuilder.forAddress(new InetSocketAddress("localhost", port))
                    .addService(this)
                    .build();
        }

        void start() throws Exception {
            server.start();
        }

        void stop() {
            server.shutdownNow();
        }

        void nextResponse(DeliberationResponse response) {
            this.pendingResponse = response;
            this.pendingError = null;
        }

        void nextError(io.grpc.Status status) {
            this.pendingError = status;
            this.pendingResponse = null;
        }

        DeliberationRequest lastRequest() {
            return lastRequest;
        }

        @Override
        public void deliberate(DeliberationRequest request,
                               StreamObserver<DeliberationResponse> observer) {
            lastRequest = request;
            if (pendingError != null) {
                io.grpc.Status err = pendingError;
                pendingError = null;
                observer.onError(err.asRuntimeException());
                return;
            }
            observer.onNext(pendingResponse);
            observer.onCompleted();
            pendingResponse = null;
        }
    }
}
