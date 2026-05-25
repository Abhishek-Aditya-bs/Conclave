package io.conclave.graph.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.graph.GraphApplication;
import io.conclave.graph.rest.GraphController.GraphFindingDto;
import io.conclave.graph.rest.GraphController.TemplateInfo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Surface-level REST smoke test. Verifies discovery + execute work end-to-end
 * against a real Neo4j (empty graph — no seed needed; we just want to see HTTP +
 * Spring + Neo4j connectivity).
 */
@Testcontainers
@SpringBootTest(classes = GraphApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class GraphRestIT {

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

    @LocalServerPort int port;
    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    @DisplayName("GET /api/v1/graph/templates lists all four built-in templates")
    void listAllTemplates() {
        List<TemplateInfo> templates = client.get()
                .uri("/api/v1/graph/templates")
                .retrieve()
                .body(new ParameterizedTypeReference<List<TemplateInfo>>() {});
        assertThat(templates).extracting(TemplateInfo::name)
                .contains(
                        "fraud_card_testing_ring",
                        "fraud_cardholder_neighborhood",
                        "security_lateral_movement",
                        "security_privileged_access");
    }

    @Test
    @DisplayName("GET /api/v1/graph/templates?domain=fraud filters to two fraud templates")
    void listFraudTemplates() {
        List<TemplateInfo> templates = client.get()
                .uri("/api/v1/graph/templates?domain=fraud")
                .retrieve()
                .body(new ParameterizedTypeReference<List<TemplateInfo>>() {});
        assertThat(templates).hasSize(2);
        assertThat(templates).extracting(TemplateInfo::domain)
                .containsOnly("fraud");
    }

    @Test
    @DisplayName("POST /api/v1/graph/templates/{name}/execute on a known template returns a finding")
    void executeKnownTemplate() {
        GraphFindingDto finding = client.post()
                .uri("/api/v1/graph/templates/fraud_card_testing_ring/execute")
                .body(Map.of("deviceFingerprint", "no_such_device"))
                .retrieve()
                .body(GraphFindingDto.class);
        assertThat(finding).isNotNull();
        assertThat(finding.templateName()).isEqualTo("fraud_card_testing_ring");
        assertThat(finding.riskSignal()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("POST on an unknown template returns 404")
    void executeUnknownReturns404() {
        HttpStatusCode status = client.post()
                .uri("/api/v1/graph/templates/does-not-exist/execute")
                .body(Map.of())
                .retrieve()
                .onStatus(s -> s.value() == 404, (req, resp) -> { /* swallow */ })
                .toBodilessEntity()
                .getStatusCode();
        assertThat(status.is4xxClientError()).isTrue();
    }
}
