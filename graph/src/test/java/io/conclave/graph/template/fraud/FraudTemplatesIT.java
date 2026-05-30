package io.conclave.graph.template.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.graph.GraphApplication;
import io.conclave.graph.domain.GraphFinding;
import io.conclave.graph.service.GraphReasonerService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for the fraud-domain Cypher templates. Spins up a real Neo4j 5
 * container (multi-arch, Apple Silicon-safe), seeds a hand-crafted graph with one
 * obvious card-testing ring + one normal cardholder, and verifies both templates
 * produce the expected findings.
 */
@Testcontainers
@SpringBootTest(classes = GraphApplication.class,
        properties = "conclave.graph.ingest.enabled=false")
class FraudTemplatesIT {

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
    @Autowired Driver driver;

    @BeforeAll
    static void seedGraph() {
        // Seeding runs once per class. Using a direct Driver here (not the Spring-managed
        // one) so we can run before the Spring context fully initializes. We connect via
        // the same coordinates Spring will use.
        try (Driver d = org.neo4j.driver.GraphDatabase.driver(
                NEO4J.getBoltUrl(),
                org.neo4j.driver.AuthTokens.basic("neo4j", "conclave-test"));
             Session session = d.session()) {

            // Card-testing ring: 5 cardholders share one device, each owns 2 cards.
            session.run("""
                    UNWIND range(1, 5) AS i
                    MERGE (d:Device {fingerprint: 'dev_compromised'})
                    MERGE (c:Cardholder {id: 'cust_ring_' + toString(i)})
                    MERGE (c)-[:USED_DEVICE]->(d)
                    WITH c, i
                    UNWIND range(1, 2) AS j
                    MERGE (card:Card {token: 'card_ring_' + toString(i) + '_' + toString(j)})
                    MERGE (c)-[:OWNS]->(card)
                    """);

            // Normal cardholder: one device, one card.
            session.run("""
                    MERGE (d:Device {fingerprint: 'dev_normal'})
                    MERGE (c:Cardholder {id: 'cust_normal'})
                    MERGE (c)-[:USED_DEVICE]->(d)
                    MERGE (card:Card {token: 'card_normal'})
                    MERGE (c)-[:OWNS]->(card)
                    """);
        }
    }

    @Test
    @DisplayName("fraud_card_testing_ring detects the seeded ring on dev_compromised")
    void detectsRing() {
        Optional<GraphFinding> found = service.execute(
                "fraud_card_testing_ring",
                Map.of("deviceFingerprint", "dev_compromised"));

        assertThat(found).isPresent();
        GraphFinding f = found.get();
        assertThat(f.templateName()).isEqualTo("fraud_card_testing_ring");
        assertThat(f.rootEntityId()).isEqualTo("dev_compromised");
        assertThat(f.domain()).isEqualTo("fraud");
        assertThat(f.attributes().get("cardholderCount")).isEqualTo(5L);
        assertThat(f.attributes().get("cardCount")).isEqualTo(10L);
        assertThat(f.riskSignal()).isGreaterThan(0.0);
        // 5 / 10 = 0.5
        assertThat(f.riskSignal()).isEqualTo(0.5);
        assertThat(f.queryLatencyMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("fraud_card_testing_ring on the normal device shows a single cardholder, zero risk")
    void normalDeviceHasNoRisk() {
        Optional<GraphFinding> found = service.execute(
                "fraud_card_testing_ring",
                Map.of("deviceFingerprint", "dev_normal"));

        assertThat(found).isPresent();
        GraphFinding f = found.get();
        assertThat(f.attributes().get("cardholderCount")).isEqualTo(1L);
        assertThat(f.attributes().get("cardCount")).isEqualTo(1L);
        assertThat(f.riskSignal()).isEqualTo(0.0);  // below the 3-cardholder threshold
    }

    @Test
    @DisplayName("fraud_card_testing_ring on an unknown device returns the empty-finding path")
    void unknownDeviceReturnsEmpty() {
        Optional<GraphFinding> found = service.execute(
                "fraud_card_testing_ring",
                Map.of("deviceFingerprint", "dev_never_existed"));

        assertThat(found).isPresent();
        // With OPTIONAL/aggregate counting, a non-existent device returns 0 counts —
        // both the "hasNext==false" path and the "all-zeros" path are valid empty
        // representations. Assert on the observable contract: zero counts, zero risk.
        GraphFinding f = found.get();
        assertThat(((Number) f.attributes().get("cardholderCount")).longValue()).isEqualTo(0L);
        assertThat(((Number) f.attributes().get("cardCount")).longValue()).isEqualTo(0L);
        assertThat(f.riskSignal()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("fraud_cardholder_neighborhood returns neighbors of a known cardholder")
    void neighborhoodReturnsNeighbors() {
        Optional<GraphFinding> found = service.execute(
                "fraud_cardholder_neighborhood",
                Map.of("cardholderId", "cust_ring_1"));

        assertThat(found).isPresent();
        GraphFinding f = found.get();
        long neighborCount = ((Number) f.attributes().get("neighborCount")).longValue();
        // Within 2 hops from cust_ring_1:
        //   hop 1: dev_compromised, card_ring_1_1, card_ring_1_2  (3 neighbors)
        //   hop 2 via dev_compromised: the 4 other cardholders   (4 neighbors)
        // The 8 cards owned by those 4 cardholders are 3 hops away — outside the
        // depth bound. Total: 7. This is the contract: bounded depth, not exhaustive.
        assertThat(neighborCount).isEqualTo(7L);
        assertThat(f.riskSignal()).isEqualTo(0.0);  // descriptive only, no risk signal
        assertThat(f.attributes().get("sample")).isInstanceOf(java.util.List.class);
    }
}
