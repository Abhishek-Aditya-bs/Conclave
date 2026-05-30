package io.conclave.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.baseline.domain.Baseline;
import io.conclave.baseline.storage.BaselineRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * p99 lookup latency test — the spec asks for {@code &lt; 20ms} at the M3 acceptance bar.
 *
 * <p>Setup: seed 10,000 baselines, then run 1000 random lookups via the repository
 * (bypassing HTTP/gRPC framing — the contract is the repository's own p99). Measures
 * in-process; CI hardware varies but Testcontainers' pgvector running locally clears
 * 20ms p99 by an order of magnitude on Apple M3.
 *
 * <p>The test prints the full p50/p95/p99 distribution so reviewers can see actuals.
 */
@Testcontainers
@SpringBootTest(classes = BaselineApplication.class,
        properties = "conclave.baseline.ingest.enabled=false")
class BaselineLatencyIT {

    private static final Logger LOG = LoggerFactory.getLogger(BaselineLatencyIT.class);
    private static final int SEED_COUNT = 10_000;
    private static final int QUERY_COUNT = 1_000;
    private static final long P99_BUDGET_MS = 20L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void overrides(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.grpc.server.port", () -> 0);
    }

    @Autowired BaselineRepository repository;

    @Test
    @DisplayName("p99 of " + QUERY_COUNT + " lookups against " + SEED_COUNT + " baselines is < 20ms")
    void p99LookupUnder20ms() {
        Random rnd = new Random(0xC0FFEEL);

        // Seed: 10K baselines.
        LOG.info("Seeding {} baselines…", SEED_COUNT);
        long t0 = System.nanoTime();
        for (int i = 0; i < SEED_COUNT; i++) {
            float[] embedding = new float[384];
            for (int j = 0; j < 384; j++) embedding[j] = rnd.nextFloat();
            repository.save(new Baseline(
                    "cust_" + i,
                    i % 2 == 0 ? "fraud" : "security",
                    embedding,
                    1L + rnd.nextInt(100),
                    Instant.now()));
        }
        LOG.info("Seed complete in {} ms", (System.nanoTime() - t0) / 1_000_000);
        assertThat(repository.count()).isGreaterThanOrEqualTo(SEED_COUNT);

        // Warm-up: 100 lookups to populate buffer cache.
        for (int i = 0; i < 100; i++) {
            int idx = rnd.nextInt(SEED_COUNT);
            repository.find(idx % 2 == 0 ? "fraud" : "security", "cust_" + idx);
        }

        // Measurement.
        long[] timings = new long[QUERY_COUNT];
        for (int i = 0; i < QUERY_COUNT; i++) {
            int idx = rnd.nextInt(SEED_COUNT);
            String domain = idx % 2 == 0 ? "fraud" : "security";
            long start = System.nanoTime();
            repository.find(domain, "cust_" + idx);
            timings[i] = System.nanoTime() - start;
        }

        Arrays.sort(timings);
        long p50ns = timings[QUERY_COUNT / 2];
        long p95ns = timings[(int) (QUERY_COUNT * 0.95)];
        long p99ns = timings[(int) (QUERY_COUNT * 0.99)];
        long maxns = timings[QUERY_COUNT - 1];

        LOG.info("Lookup latency over {} queries / {} baselines: p50={}us, p95={}us, p99={}us, max={}us",
                QUERY_COUNT, SEED_COUNT,
                p50ns / 1_000, p95ns / 1_000, p99ns / 1_000, maxns / 1_000);

        long p99ms = p99ns / 1_000_000;
        assertThat(p99ms)
                .as("p99 lookup latency must be < %d ms (got %d ms, full distribution above)",
                        P99_BUDGET_MS, p99ms)
                .isLessThan(P99_BUDGET_MS);
    }
}
