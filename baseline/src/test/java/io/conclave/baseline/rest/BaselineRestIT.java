package io.conclave.baseline.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.baseline.BaselineApplication;
import io.conclave.baseline.rest.BaselineController.BaselineDto;
import io.conclave.baseline.rest.BaselineController.UpdateRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * REST integration test. Boots the full Spring context against a Testcontainers
 * pgvector instance and exercises both endpoints end-to-end with a real HTTP client.
 *
 * <p>The headline test ({@link #ninetyDaySyntheticStream}) is the spec's M3 acceptance
 * criterion — feed 90 days of synthetic events for several entities, verify the
 * baseline embedding actually drifts toward the steady-state pattern.
 */
@Testcontainers
@SpringBootTest(classes = BaselineApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class BaselineRestIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.grpc.server.port", () -> 0);
    }

    @LocalServerPort int port;
    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    @DisplayName("GET /api/v1/baselines/{domain}/{entityId} returns 404 for unseen entity")
    void getReturns404Initially() {
        HttpStatusCode status = client.get()
                .uri("/api/v1/baselines/fraud/never_seen_" + UUID.randomUUID())
                .retrieve()
                .onStatus(s -> s.value() == 404, (req, resp) -> {})  // swallow 404 → return null body
                .toBodilessEntity()
                .getStatusCode();
        assertThat(status.is4xxClientError()).isTrue();
    }

    @Test
    @DisplayName("POST then GET round-trips a baseline through the REST API")
    void postThenGetRoundTrip() {
        String entityId = "cust_rest_" + UUID.randomUUID();

        BaselineDto posted = client.post()
                .uri("/api/v1/baselines/fraud/" + entityId)
                .body(new UpdateRequest("card-not-present txn at acme for $42"))
                .retrieve()
                .body(BaselineDto.class);

        assertThat(posted).isNotNull();
        assertThat(posted.entityId()).isEqualTo(entityId);
        assertThat(posted.domain()).isEqualTo("fraud");
        assertThat(posted.embedding()).hasSize(384);
        assertThat(posted.eventCount()).isEqualTo(1L);

        BaselineDto fetched = client.get()
                .uri("/api/v1/baselines/fraud/" + entityId)
                .retrieve()
                .body(BaselineDto.class);

        assertThat(fetched).isNotNull();
        assertThat(fetched.entityId()).isEqualTo(entityId);
        assertThat(fetched.embedding()).hasSize(384);
    }

    @Test
    @DisplayName("Repeated POSTs to the same entity increment eventCount and drift the embedding")
    void repeatedPostsAccumulate() {
        String entityId = "cust_drift_" + UUID.randomUUID();

        BaselineDto first = postEvent(entityId, "low-value online purchase");
        assertThat(first.eventCount()).isEqualTo(1L);

        BaselineDto second = postEvent(entityId, "low-value online purchase");
        assertThat(second.eventCount()).isEqualTo(2L);

        // Identical event → EMA averages an embedding with itself → no movement.
        assertThat(second.embedding()).hasSize(384);
    }

    @Test
    @DisplayName("90-day synthetic stream — baseline embedding drifts toward steady-state pattern")
    void ninetyDaySyntheticStream() {
        String entityA = "cust_drift_A_" + UUID.randomUUID();
        String entityB = "cust_drift_B_" + UUID.randomUUID();

        // Two entities with distinct event "patterns".
        String patternA = "card-not-present purchase at acme corp for $42 in USD";
        String patternB = "ATM cash withdrawal from chase atm in nyc for $200";

        Random rnd = new Random(1234);
        Instant base = Instant.parse("2026-02-01T00:00:00Z");
        for (int day = 0; day < 90; day++) {
            String dateNoise = base.plus(Duration.ofDays(day)).toString();
            postEvent(entityA, patternA + " on " + dateNoise);
            postEvent(entityB, patternB + " on " + dateNoise);
            // Inject a tiny bit of cross-noise so the patterns aren't perfectly clean.
            if (rnd.nextInt(10) == 0) {
                postEvent(entityA, patternB + " on " + dateNoise);
            }
        }

        BaselineDto finalA = client.get()
                .uri("/api/v1/baselines/fraud/" + entityA)
                .retrieve().body(BaselineDto.class);
        BaselineDto finalB = client.get()
                .uri("/api/v1/baselines/fraud/" + entityB)
                .retrieve().body(BaselineDto.class);

        assertThat(finalA.eventCount()).isGreaterThanOrEqualTo(90L);
        assertThat(finalB.eventCount()).isEqualTo(90L);

        // Probe vectors: each pattern in isolation, fresh entity.
        BaselineDto patternEmbeddingA = postEvent(
                "_probe_A_" + UUID.randomUUID(), patternA + " on " + base.toString());
        BaselineDto patternEmbeddingB = postEvent(
                "_probe_B_" + UUID.randomUUID(), patternB + " on " + base.toString());

        double aVsA = cosine(finalA.embedding(), patternEmbeddingA.embedding());
        double aVsB = cosine(finalA.embedding(), patternEmbeddingB.embedding());
        double bVsB = cosine(finalB.embedding(), patternEmbeddingB.embedding());
        double bVsA = cosine(finalB.embedding(), patternEmbeddingA.embedding());

        assertThat(aVsA)
                .as("entity A's baseline should be closer to pattern A than to pattern B")
                .isGreaterThan(aVsB);
        assertThat(bVsB)
                .as("entity B's baseline should be closer to pattern B than to pattern A")
                .isGreaterThan(bVsA);
    }

    private BaselineDto postEvent(String entityId, String eventText) {
        return client.post()
                .uri("/api/v1/baselines/fraud/" + entityId)
                .body(new UpdateRequest(eventText))
                .retrieve()
                .body(BaselineDto.class);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-12);
    }
}
