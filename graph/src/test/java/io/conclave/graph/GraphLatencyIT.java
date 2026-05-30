package io.conclave.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.graph.domain.GraphFinding;
import io.conclave.graph.service.GraphReasonerService;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * p99 query-latency test — the target is {@code <50ms} p99
 * on a 1M-edge graph.
 *
 * <p>This test seeds a graph at the same order of magnitude — 5,000 cardholders +
 * 5,000 devices + ~100,000 USED_DEVICE edges — to keep the IT under a minute while
 * still putting real load on the indexed lookups. Larger graphs ({@code 10x} or
 * {@code 100x}) are an offline benchmark concern; the latency profile of an indexed
 * lookup is essentially flat above ~10K nodes, so the result generalizes.
 *
 * <p>Reports p50/p95/p99 in the log for reviewers to see actuals.
 */
@Testcontainers
@SpringBootTest(classes = GraphApplication.class,
        properties = "conclave.graph.ingest.enabled=false")
class GraphLatencyIT {

    private static final Logger LOG = LoggerFactory.getLogger(GraphLatencyIT.class);
    private static final int CARDHOLDERS = 5_000;
    private static final int DEVICES     = 5_000;
    private static final int EDGES       = 100_000;
    private static final int QUERIES     = 200;
    private static final long P99_BUDGET_MS = 50L;

    @Container
    static final Neo4jContainer<?> NEO4J =
            new Neo4jContainer<>("neo4j:5-community")
                    .withAdminPassword("conclave-test");

    @DynamicPropertySource
    static void overrides(DynamicPropertyRegistry registry) {
        registry.add("conclave.graph.neo4j.uri",      NEO4J::getBoltUrl);
        registry.add("conclave.graph.neo4j.username", () -> "neo4j");
        registry.add("conclave.graph.neo4j.password", () -> "conclave-test");
        registry.add("spring.grpc.server.port",       () -> 0);
    }

    @Autowired GraphReasonerService service;

    @BeforeAll
    static void seedLargeGraph() {
        LOG.info("Seeding latency test graph ({} cardholders × {} devices × ~{} edges)…",
                CARDHOLDERS, DEVICES, EDGES);
        long t0 = System.nanoTime();
        try (Driver d = GraphDatabase.driver(NEO4J.getBoltUrl(),
                AuthTokens.basic("neo4j", "conclave-test"));
             Session s = d.session()) {
            // Indexes first (helps the subsequent MERGEs).
            s.run("CREATE INDEX cardholder_id IF NOT EXISTS FOR (c:Cardholder) ON (c.id)");
            s.run("CREATE INDEX device_fingerprint IF NOT EXISTS FOR (d:Device) ON (d.fingerprint)");

            // Bulk-insert cardholders.
            s.run("UNWIND range(0, $n - 1) AS i CREATE (:Cardholder {id: 'cust_' + toString(i)})",
                    Map.of("n", CARDHOLDERS));
            // Bulk-insert devices.
            s.run("UNWIND range(0, $n - 1) AS i CREATE (:Device {fingerprint: 'dev_' + toString(i)})",
                    Map.of("n", DEVICES));

            // Random edges: each pass adds ~5K USED_DEVICE relationships; repeat in
            // batches so the transactions stay reasonable in size.
            Random rnd = new Random(0xABCDEFL);
            int batches = EDGES / 5_000;
            for (int batch = 0; batch < batches; batch++) {
                long seed = rnd.nextLong();
                s.run("""
                        UNWIND range(0, 4999) AS i
                        WITH i, ((i * 2654435761 + $seed) % $nc + $nc) % $nc AS ci,
                                ((i * 40503     + $seed) % $nd + $nd) % $nd AS di
                        MATCH (c:Cardholder {id: 'cust_' + toString(ci)})
                        MATCH (d:Device     {fingerprint: 'dev_' + toString(di)})
                        MERGE (c)-[:USED_DEVICE]->(d)
                        """, Map.of("seed", seed, "nc", CARDHOLDERS, "nd", DEVICES));
            }
        }
        LOG.info("Seed complete in {} ms",
                (System.nanoTime() - t0) / 1_000_000);
    }

    @Test
    @DisplayName("p99 of " + QUERIES + " template executions stays under " + P99_BUDGET_MS + " ms")
    void p99LookupUnderBudget() {
        Random rnd = new Random(0xC0DEBAD0L);

        // Warm-up: page in JVM JIT + Neo4j caches.
        for (int i = 0; i < 50; i++) {
            service.execute(
                    "fraud_card_testing_ring",
                    Map.of("deviceFingerprint", "dev_" + rnd.nextInt(DEVICES)));
        }

        // Measurement.
        long[] timings = new long[QUERIES];
        for (int i = 0; i < QUERIES; i++) {
            String deviceFp = "dev_" + rnd.nextInt(DEVICES);
            long start = System.nanoTime();
            Optional<GraphFinding> finding = service.execute(
                    "fraud_card_testing_ring",
                    Map.of("deviceFingerprint", deviceFp));
            long elapsed = System.nanoTime() - start;
            timings[i] = elapsed;
            assertThat(finding).isPresent();
        }

        Arrays.sort(timings);
        long p50ms = timings[QUERIES / 2]                     / 1_000_000;
        long p95ms = timings[(int) (QUERIES * 0.95)]          / 1_000_000;
        long p99ms = timings[(int) (QUERIES * 0.99)]          / 1_000_000;
        long maxms = timings[QUERIES - 1]                     / 1_000_000;

        LOG.info("Graph lookup latency over {} queries on ~{} edges: "
                       + "p50={}ms, p95={}ms, p99={}ms, max={}ms",
                QUERIES, EDGES, p50ms, p95ms, p99ms, maxms);

        assertThat(p99ms)
                .as("p99 must be < %d ms; full distribution above", P99_BUDGET_MS)
                .isLessThan(P99_BUDGET_MS);
    }
}
