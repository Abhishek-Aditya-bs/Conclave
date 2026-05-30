package io.conclave.graph.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.graph.GraphApplication;
import io.conclave.graph.proto.ExecuteTemplateRequest;
import io.conclave.graph.proto.ExecuteTemplateResponse;
import io.conclave.graph.proto.GraphReasonerServiceGrpc;
import io.conclave.graph.proto.ListTemplatesRequest;
import io.conclave.graph.proto.ListTemplatesResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * gRPC integration test. Same shape as M3's {@code BaselineGrpcIT}; pins the gRPC
 * port to a known value and connects a blocking stub.
 */
@Testcontainers
@SpringBootTest(classes = GraphApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "conclave.graph.ingest.enabled=false")
class GraphGrpcIT {

    private static final int GRPC_PORT = 29092;

    @Container
    static final Neo4jContainer<?> NEO4J =
            new Neo4jContainer<>("neo4j:5-community")
                    .withAdminPassword("conclave-test");

    @DynamicPropertySource
    static void overrides(DynamicPropertyRegistry registry) {
        registry.add("conclave.graph.neo4j.uri",      NEO4J::getBoltUrl);
        registry.add("conclave.graph.neo4j.username", () -> "neo4j");
        registry.add("conclave.graph.neo4j.password", () -> "conclave-test");
        registry.add("spring.grpc.server.port",       () -> GRPC_PORT);
    }

    @Value("${spring.grpc.server.port}") int grpcPort;

    private ManagedChannel channel;
    private GraphReasonerServiceGrpc.GraphReasonerServiceBlockingStub stub;

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder
                .forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        stub = GraphReasonerServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void disconnect() throws InterruptedException {
        if (channel != null) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("ListTemplates over gRPC returns all four templates")
    void listAll() {
        ListTemplatesResponse resp = stub.listTemplates(
                ListTemplatesRequest.newBuilder().build());
        assertThat(resp.getTemplatesCount()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("ExecuteTemplate over gRPC returns a finding with JSON attributes")
    void executeReturnsFinding() {
        ExecuteTemplateResponse resp = stub.executeTemplate(
                ExecuteTemplateRequest.newBuilder()
                        .setTemplateName("security_lateral_movement")
                        .putParams("principalId", "user_never_existed")
                        .build());
        assertThat(resp.hasFinding()).isTrue();
        assertThat(resp.getFinding().getTemplateName()).isEqualTo("security_lateral_movement");
        assertThat(resp.getFinding().getDomain()).isEqualTo("security");
        assertThat(resp.getFinding().getAttributesJson()).contains("hostCount");
        assertThat(resp.getFinding().getRiskSignal()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("ExecuteTemplate on an unknown name returns the TemplateNotFound branch")
    void executeUnknownReturnsNotFound() {
        ExecuteTemplateResponse resp = stub.executeTemplate(
                ExecuteTemplateRequest.newBuilder()
                        .setTemplateName("does-not-exist")
                        .build());
        assertThat(resp.hasFinding()).isFalse();
        assertThat(resp.hasError()).isTrue();
        assertThat(resp.getError().getTemplateName()).isEqualTo("does-not-exist");
        assertThat(resp.getError().getMessage()).contains("No template");
    }
}
