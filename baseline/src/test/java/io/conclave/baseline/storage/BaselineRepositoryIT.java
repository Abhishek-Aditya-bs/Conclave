package io.conclave.baseline.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.conclave.baseline.BaselineApplication;
import io.conclave.baseline.domain.Baseline;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Storage-layer integration test. Spins up the official multi-arch pgvector image
 * (works natively on Apple Silicon AND linux/amd64 CI), boots a tiny Spring context
 * holding only {@code JdbcBaselineRepository} + {@code SchemaInitializer}, and round-trips
 * vectors through Postgres.
 */
@Testcontainers
@SpringBootTest(classes = BaselineApplication.class)
class BaselineRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("conclave")
            .withUsername("conclave")
            .withPassword("conclave");

    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Pin gRPC to a random port so concurrent ITs don't collide.
        registry.add("spring.grpc.server.port", () -> 0);
    }

    @Autowired BaselineRepository repository;

    @Test
    @DisplayName("save() then find() round-trips a 384-dim vector through pgvector")
    void roundTrip() {
        float[] embedding = sequentialVector(384);
        Baseline baseline = new Baseline(
                "cust_round_trip",
                "fraud",
                embedding,
                42L,
                Instant.parse("2026-05-25T10:00:00Z"));

        repository.save(baseline);

        Optional<Baseline> loaded = repository.find("fraud", "cust_round_trip");
        assertThat(loaded).isPresent();
        Baseline fromDb = loaded.get();
        assertThat(fromDb.entityId()).isEqualTo(baseline.entityId());
        assertThat(fromDb.domain()).isEqualTo(baseline.domain());
        assertThat(fromDb.eventCount()).isEqualTo(42L);
        // Postgres timestamptz has microsecond precision; compare with tolerance
        assertThat(fromDb.lastUpdated())
                .isCloseTo(baseline.lastUpdated(), within(1, ChronoUnit.MILLIS));
        assertThat(fromDb.embedding()).hasSize(384);
        for (int i = 0; i < embedding.length; i++) {
            assertThat(fromDb.embedding()[i]).isCloseTo(embedding[i], within(1e-6f));
        }
    }

    @Test
    @DisplayName("save() on an existing (domain, entityId) updates the row in place")
    void upsertReplacesExisting() {
        Baseline first = new Baseline(
                "cust_upsert",
                "fraud",
                sequentialVector(384),
                1L,
                Instant.parse("2026-05-25T10:00:00Z"));
        repository.save(first);

        float[] newer = sequentialVector(384);
        for (int i = 0; i < newer.length; i++) newer[i] *= -1f;  // distinct vector
        Baseline second = new Baseline(
                "cust_upsert",
                "fraud",
                newer,
                17L,
                Instant.parse("2026-05-26T11:00:00Z"));
        repository.save(second);

        Optional<Baseline> loaded = repository.find("fraud", "cust_upsert");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().eventCount()).isEqualTo(17L);
        // sequentialVector produces i/dim, then we negated; index 0 → 0, index 1 → -1/dim.
        assertThat(loaded.get().embedding()[0]).isCloseTo(0.0f, within(1e-6f));
        assertThat(loaded.get().embedding()[1]).isCloseTo(-1.0f / 384, within(1e-6f));
    }

    @Test
    @DisplayName("find() returns empty Optional for an unseen entity")
    void findReturnsEmptyWhenAbsent() {
        Optional<Baseline> loaded = repository.find("fraud", "never-seen-this-entity");
        assertThat(loaded).isEmpty();
    }

    @Test
    @DisplayName("Same entity-id in two domains are stored independently")
    void entityIdNotUniqueAcrossDomains() {
        Baseline fraud = new Baseline("eve", "fraud", sequentialVector(384), 1L, Instant.now());
        Baseline security = new Baseline("eve", "security", sequentialVector(384), 1L, Instant.now());
        repository.save(fraud);
        repository.save(security);

        assertThat(repository.find("fraud", "eve")).isPresent();
        assertThat(repository.find("security", "eve")).isPresent();
        assertThat(repository.find("fraud", "eve")).isNotEqualTo(repository.find("security", "eve"));
    }

    private static float[] sequentialVector(int dim) {
        float[] arr = new float[dim];
        for (int i = 0; i < dim; i++) arr[i] = (float) i / dim;
        return arr;
    }
}
