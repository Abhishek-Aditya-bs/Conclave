package io.conclave.baseline.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.baseline.BaselineApplication;
import io.conclave.baseline.proto.BaselineServiceGrpc;
import io.conclave.baseline.proto.GetBaselineRequest;
import io.conclave.baseline.proto.GetBaselineResponse;
import io.conclave.baseline.proto.UpdateBaselineRequest;
import io.conclave.baseline.proto.UpdateBaselineResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.UUID;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * gRPC integration test. The Spring context boots the real gRPC server (on a fixed test
 * port to avoid a port-discovery dance), connects a blocking stub over plaintext, and
 * exercises both RPCs.
 */
@Testcontainers
@SpringBootTest(classes = BaselineApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "conclave.baseline.ingest.enabled=false")
class BaselineGrpcIT {

    private static final int GRPC_PORT = 19092;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void overrides(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.grpc.server.port", () -> GRPC_PORT);
    }

    @Value("${spring.grpc.server.port}") int grpcPort;

    private ManagedChannel channel;
    private BaselineServiceGrpc.BaselineServiceBlockingStub stub;

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder
                .forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        stub = BaselineServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void disconnect() throws InterruptedException {
        if (channel != null) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("GetBaseline returns NotFound for an unseen entity")
    void getReturnsNotFound() {
        GetBaselineResponse resp = stub.getBaseline(GetBaselineRequest.newBuilder()
                .setDomain("fraud")
                .setEntityId("never_seen_" + UUID.randomUUID())
                .build());
        assertThat(resp.hasNotFound()).isTrue();
        assertThat(resp.hasBaseline()).isFalse();
        assertThat(resp.getNotFound().getMessage()).contains("no baseline");
    }

    @Test
    @DisplayName("UpdateBaseline then GetBaseline round-trips a baseline over gRPC")
    void updateThenGet() {
        String entityId = "cust_grpc_" + UUID.randomUUID();

        UpdateBaselineResponse update = stub.updateBaseline(UpdateBaselineRequest.newBuilder()
                .setDomain("fraud")
                .setEntityId(entityId)
                .setEventText("transaction at acme for $42")
                .build());

        assertThat(update.getBaseline().getEntityId()).isEqualTo(entityId);
        assertThat(update.getBaseline().getDomain()).isEqualTo("fraud");
        assertThat(update.getBaseline().getEmbeddingCount()).isEqualTo(384);
        assertThat(update.getBaseline().getEventCount()).isEqualTo(1L);

        GetBaselineResponse get = stub.getBaseline(GetBaselineRequest.newBuilder()
                .setDomain("fraud")
                .setEntityId(entityId)
                .build());

        assertThat(get.hasBaseline()).isTrue();
        assertThat(get.getBaseline().getEntityId()).isEqualTo(entityId);
        assertThat(get.getBaseline().getEmbeddingCount()).isEqualTo(384);
    }

    @Test
    @DisplayName("Successive UpdateBaseline calls increment the event count")
    void successiveUpdatesAccumulate() {
        String entityId = "cust_grpc_inc_" + UUID.randomUUID();

        UpdateBaselineResponse first = stub.updateBaseline(UpdateBaselineRequest.newBuilder()
                .setDomain("fraud")
                .setEntityId(entityId)
                .setEventText("evt 1")
                .build());
        assertThat(first.getBaseline().getEventCount()).isEqualTo(1L);

        UpdateBaselineResponse second = stub.updateBaseline(UpdateBaselineRequest.newBuilder()
                .setDomain("fraud")
                .setEntityId(entityId)
                .setEventText("evt 2")
                .build());
        assertThat(second.getBaseline().getEventCount()).isEqualTo(2L);

        UpdateBaselineResponse third = stub.updateBaseline(UpdateBaselineRequest.newBuilder()
                .setDomain("fraud")
                .setEntityId(entityId)
                .setEventText("evt 3")
                .build());
        assertThat(third.getBaseline().getEventCount()).isEqualTo(3L);
    }
}
