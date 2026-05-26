package io.conclave.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conclave.ConclaveApplication;
import io.conclave.deliberation.proto.ContributingFactor;
import io.conclave.deliberation.proto.Decision;
import io.conclave.deliberation.proto.DeliberationRequest;
import io.conclave.deliberation.proto.DeliberationResponse;
import io.conclave.deliberation.proto.DeliberationServiceGrpc;
import io.conclave.orchestrator.domain.ContributingFactorRecord;
import io.conclave.orchestrator.domain.DecisionRecord;
import io.conclave.orchestrator.storage.DecisionRepository;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end IT for the M7 audit API.
 *
 * <p>Boots the full Spring context (M1 + M2 + M6 + M7) under fraud profile,
 * seeds rows via the M6 {@link DecisionRepository}, then hits the M7 REST
 * endpoints over a real HTTP transport ({@link RestClient} with the
 * Spring-assigned local port).
 *
 * <p>The replay test wires a Netty in-process mock M5 the orchestrator's
 * {@code DeliberationClient} can connect to — same setup the M6 IT uses.
 */
@SpringBootTest(
        classes = ConclaveApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "conclave.orchestrator.deliberation-deadline-ms=5000",
        })
@ActiveProfiles("fraud")
@Testcontainers
class AuditApiIT {

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    static MockM5 mockM5;
    static int mockM5Port;

    @BeforeAll
    static void startMockM5() throws Exception {
        mockM5Port = pickFreePort();
        mockM5 = new MockM5(mockM5Port);
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

    @LocalServerPort int port;
    @Autowired DecisionRepository writer;

    private RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private DecisionRecord seed(String eventId, String domain, String entity,
                                double score, String label, String enriched) {
        DecisionRecord d = new DecisionRecord(
                UUID.randomUUID(), eventId, domain, entity, score, label,
                "Markdown explanation for " + eventId + ".",
                List.of(new ContributingFactorRecord("graph_ring_detected", 0.7, "ev")),
                123L, "anthropic", "claude-haiku-4-5-20251001",
                enriched, Instant.now());
        writer.save(d);
        return d;
    }

    @Test
    void list_filters_by_domain_and_score() throws Exception {
        seed("evt-list-fraud-low", "fraud", "c1", 0.10, "ALLOW", "{}");
        seed("evt-list-fraud-mid", "fraud", "c2", 0.55, "REVIEW", "{}");
        seed("evt-list-fraud-high", "fraud", "c3", 0.92, "BLOCK", "{}");
        seed("evt-list-sec", "security", "alice@corp", 0.65, "REVIEW", "{}");

        String body = http().get()
                .uri("/api/v1/decisions?domain=fraud&min_score=0.5&limit=10")
                .retrieve()
                .body(String.class);

        JsonNode payload = new ObjectMapper().readTree(body);
        // total counts ALL fraud rows with score>=0.5 inserted earlier; could be
        // ≥2 if previous tests in this class also ran.
        assertThat(payload.get("total").asInt()).isGreaterThanOrEqualTo(2);
        JsonNode items = payload.get("items");
        assertThat(items.isArray()).isTrue();
        for (JsonNode item : items) {
            assertThat(item.get("domain").asText()).isEqualTo("fraud");
            assertThat(item.get("score").asDouble()).isGreaterThanOrEqualTo(0.5);
        }
        // baseline_entity_id surfaced in the list view.
        if (items.size() > 0) {
            assertThat(items.get(0).has("baseline_entity_id")).isTrue();
        }
    }

    @Test
    void list_paginates() throws Exception {
        for (int i = 0; i < 7; i++) {
            seed("evt-page-" + i, "fraud", "c-page", 0.4 + i * 0.01, "REVIEW", "{}");
        }
        String body = http().get()
                .uri("/api/v1/decisions?baseline_entity_id=c-page&limit=3&offset=0")
                .retrieve()
                .body(String.class);
        JsonNode payload = new ObjectMapper().readTree(body);
        assertThat(payload.get("items").size()).isLessThanOrEqualTo(3);
        assertThat(payload.get("limit").asInt()).isEqualTo(3);
        assertThat(payload.get("offset").asInt()).isZero();
        assertThat(payload.get("total").asInt()).isGreaterThanOrEqualTo(7);
    }

    @Test
    void detail_returns_full_payload() throws Exception {
        DecisionRecord seeded = seed("evt-detail-1", "fraud", "c-detail",
                0.83, "BLOCK", "{\"eventId\":\"evt-detail-1\"}");

        String body = http().get()
                .uri("/api/v1/decisions/" + seeded.decisionId())
                .retrieve()
                .body(String.class);
        JsonNode payload = new ObjectMapper().readTree(body);
        assertThat(payload.get("event_id").asText()).isEqualTo("evt-detail-1");
        assertThat(payload.get("baseline_entity_id").asText()).isEqualTo("c-detail");
        assertThat(payload.get("verdict_label").asText()).isEqualTo("BLOCK");
        // The detail view includes the markdown + factors + enriched_event_json.
        assertThat(payload.get("verdict_explanation_md").asText()).contains("evt-detail-1");
        JsonNode factors = payload.get("contributing_factors");
        assertThat(factors.isArray()).isTrue();
        assertThat(factors.get(0).get("name").asText()).isEqualTo("graph_ring_detected");
        assertThat(payload.get("enriched_event_json").asText()).contains("evt-detail-1");
    }

    @Test
    void detail_404_for_unknown_id() {
        UUID missing = UUID.randomUUID();
        try {
            http().get()
                    .uri("/api/v1/decisions/" + missing)
                    .retrieve()
                    .body(String.class);
            throw new AssertionError("expected 404");
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            JsonNode body;
            try {
                body = new ObjectMapper().readTree(e.getResponseBodyAsString());
            } catch (Exception parse) {
                throw new AssertionError("unparseable 404 body", parse);
            }
            assertThat(body.get("code").asText()).isEqualTo("decision_not_found");
        }
    }

    @Test
    void replay_returns_fresh_decision_without_persisting() throws Exception {
        DecisionRecord seeded = seed("evt-replay-1", "fraud", "c-replay", 0.83, "BLOCK",
                "{\"eventId\":\"evt-replay-1\",\"baselineEntityId\":\"c-replay\","
                + "\"graphEntityIds\":[\"c-replay\",\"dev-1\",\"1.2.3.4\"]}");

        mockM5.nextResponse(DeliberationResponse.newBuilder()
                .setDecision(Decision.newBuilder()
                        .setScore(0.40)
                        .setVerdictLabel("REVIEW")
                        .setVerdictExplanationMd("Replay verdict — score drifted.")
                        .addContributingFactors(ContributingFactor.newBuilder()
                                .setName("behavioral_anomaly")
                                .setWeight(0.4)
                                .setEvidence("baseline drift"))
                        .setLatencyMs(150L)
                        .setJudgeProvider("anthropic")
                        .setJudgeModel("claude-haiku-4-5-20251001"))
                .build());

        String body = http().post()
                .uri("/api/v1/decisions/" + seeded.decisionId() + "/replay")
                .retrieve()
                .body(String.class);
        JsonNode payload = new ObjectMapper().readTree(body);

        // Replay returns a DIFFERENT decision_id from the seeded row.
        assertThat(payload.get("decision_id").asText()).isNotEqualTo(seeded.decisionId().toString());
        assertThat(payload.get("event_id").asText()).isEqualTo("evt-replay-1");
        assertThat(payload.get("verdict_label").asText()).isEqualTo("REVIEW");
        assertThat(payload.get("score").asDouble()).isEqualTo(0.40);

        // The orchestrator forwarded the correct evidence to M5.
        DeliberationRequest sentToM5 = mockM5.lastRequest();
        assertThat(sentToM5.getEventId()).isEqualTo("evt-replay-1");
        assertThat(sentToM5.getBaselineEntityId()).isEqualTo("c-replay");
        assertThat(sentToM5.getGraphEntityIdsList())
                .containsExactly("c-replay", "dev-1", "1.2.3.4");

        // The replayed Decision is NOT persisted — the original row remains
        // unchanged.
        String reread = http().get()
                .uri("/api/v1/decisions/" + seeded.decisionId())
                .retrieve()
                .body(String.class);
        JsonNode rereadPayload = new ObjectMapper().readTree(reread);
        assertThat(rereadPayload.get("verdict_label").asText()).isEqualTo("BLOCK");
        assertThat(rereadPayload.get("score").asDouble()).isEqualTo(0.83);
    }

    @Test
    void replay_404_for_unknown_id() {
        UUID missing = UUID.randomUUID();
        try {
            http().post().uri("/api/v1/decisions/" + missing + "/replay")
                    .retrieve().body(String.class);
            throw new AssertionError("expected 404");
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound expected) {
            // good — handler fired
        }
    }

    @Test
    void list_400_on_invalid_score_bound() {
        try {
            http().get()
                    .uri("/api/v1/decisions?min_score=2.0")
                    .retrieve()
                    .body(String.class);
            throw new AssertionError("expected 400");
        } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
            JsonNode body;
            try {
                body = new ObjectMapper().readTree(e.getResponseBodyAsString());
            } catch (Exception parse) {
                throw new AssertionError("unparseable 400 body", parse);
            }
            assertThat(body.get("code").asText()).isEqualTo("invalid_argument");
        }
    }

    private static int pickFreePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Real Netty mock M5; one-shot scripted response per test. */
    static final class MockM5 extends DeliberationServiceGrpc.DeliberationServiceImplBase {
        private final Server server;
        private volatile DeliberationResponse pending;
        private volatile DeliberationRequest lastRequest;

        MockM5(int port) {
            server = NettyServerBuilder.forAddress(new InetSocketAddress("localhost", port))
                    .addService(this).build();
        }

        void start() throws Exception { server.start(); }
        void stop() { server.shutdownNow(); }
        void nextResponse(DeliberationResponse r) { this.pending = r; }
        DeliberationRequest lastRequest() { return lastRequest; }

        @Override
        public void deliberate(DeliberationRequest request,
                               StreamObserver<DeliberationResponse> observer) {
            lastRequest = request;
            observer.onNext(pending);
            observer.onCompleted();
            pending = null;
        }
    }
}
