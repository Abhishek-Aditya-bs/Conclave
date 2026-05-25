package io.conclave.graph.template.security;

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
 * Integration tests for the security-domain Cypher templates. Seeds a SOC graph with
 * one normal user, one lateral-mover, one privileged admin, and verifies the
 * lateral-movement + privileged-access templates fire correctly.
 */
@Testcontainers
@SpringBootTest(classes = GraphApplication.class)
class SecurityTemplatesIT {

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
        try (Driver d = org.neo4j.driver.GraphDatabase.driver(
                NEO4J.getBoltUrl(),
                org.neo4j.driver.AuthTokens.basic("neo4j", "conclave-test"));
             Session session = d.session()) {

            // Normal user: accessed only their own workstation.
            session.run("""
                    MERGE (p:Principal {id: 'user_normal'})
                    MERGE (h:Host      {id: 'host_workstation_1'})
                    MERGE (p)-[:ACCESSED]->(h)
                    """);

            // Lateral mover: accessed 6 distinct hosts.
            session.run("""
                    MERGE (p:Principal {id: 'user_attacker'})
                    WITH p
                    UNWIND range(1, 6) AS i
                    MERGE (h:Host {id: 'host_pwned_' + toString(i)})
                    MERGE (p)-[:ACCESSED]->(h)
                    """);

            // Admin: accessed one host AND a restricted resource.
            session.run("""
                    MERGE (p:Principal {id: 'user_admin'})
                    MERGE (h:Host      {id: 'host_admin_console'})
                    MERGE (r:Resource  {id: 'res_finance_db', sensitivity: 'restricted'})
                    MERGE (low:Resource {id: 'res_intranet',   sensitivity: 'low'})
                    MERGE (p)-[:ACCESSED]->(h)
                    MERGE (p)-[:ACCESSED]->(r)
                    MERGE (p)-[:ACCESSED]->(low)
                    """);
        }
    }

    @Test
    @DisplayName("security_lateral_movement flags the attacker who touched 6 hosts")
    void lateralMoverIsDetected() {
        Optional<GraphFinding> found = service.execute(
                "security_lateral_movement",
                Map.of("principalId", "user_attacker"));

        assertThat(found).isPresent();
        GraphFinding f = found.get();
        assertThat(f.attributes().get("hostCount")).isEqualTo(6L);
        // risk = min(1.0, 6 / 20.0) = 0.3
        assertThat(f.riskSignal()).isEqualTo(0.3);
        assertThat(f.attributes().get("sampleHosts")).isInstanceOf(java.util.List.class);
    }

    @Test
    @DisplayName("security_lateral_movement does NOT fire on the normal user (1 host)")
    void normalUserSafe() {
        Optional<GraphFinding> found = service.execute(
                "security_lateral_movement",
                Map.of("principalId", "user_normal"));

        assertThat(found).isPresent();
        GraphFinding f = found.get();
        assertThat(f.attributes().get("hostCount")).isEqualTo(1L);
        assertThat(f.riskSignal()).isEqualTo(0.0);  // below 5-host threshold
    }

    @Test
    @DisplayName("security_lateral_movement on an unknown principal returns zero-counts finding")
    void unknownPrincipalReturnsEmpty() {
        Optional<GraphFinding> found = service.execute(
                "security_lateral_movement",
                Map.of("principalId", "user_never_existed"));

        assertThat(found).isPresent();
        GraphFinding f = found.get();
        assertThat(((Number) f.attributes().get("hostCount")).longValue()).isEqualTo(0L);
        assertThat(f.riskSignal()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("security_privileged_access fires on access to a restricted resource")
    void privilegedAccessRaisesFlag() {
        Optional<GraphFinding> found = service.execute(
                "security_privileged_access",
                Map.of("principalId", "user_admin"));

        assertThat(found).isPresent();
        GraphFinding f = found.get();
        // Only the 'restricted'-tagged resource counts; 'low' is filtered out.
        assertThat(f.attributes().get("sensitiveCount")).isEqualTo(1L);
        // 0.4 + 0.05 * 1 = 0.45
        assertThat(f.riskSignal()).isEqualTo(0.45);
    }

    @Test
    @DisplayName("security_privileged_access does NOT fire on a user with no sensitive access")
    void normalUserNoPrivilegedAccess() {
        Optional<GraphFinding> found = service.execute(
                "security_privileged_access",
                Map.of("principalId", "user_normal"));

        assertThat(found).isPresent();
        GraphFinding f = found.get();
        assertThat(((Number) f.attributes().get("sensitiveCount")).longValue()).isEqualTo(0L);
        assertThat(f.riskSignal()).isEqualTo(0.0);
    }
}
