package io.conclave.orchestrator.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.conclave.deliberation.proto.ContributingFactor;
import io.conclave.deliberation.proto.Decision;
import io.conclave.deliberation.proto.DeliberationRequest;
import io.conclave.deliberation.proto.DeliberationResponse;
import io.conclave.deliberation.proto.DeliberationServiceGrpc;
import io.conclave.orchestrator.domain.DecisionRecord;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeliberationClientTest {

    private Server server;
    private ManagedChannel channel;
    private TestDeliberationService servicer;

    @BeforeEach
    void startInProcess() throws Exception {
        servicer = new TestDeliberationService();
        String name = "test-" + UUID.randomUUID();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(servicer)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void shutdown() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    @Test
    void happy_path_translates_response_to_record() {
        servicer.responseOnce(DeliberationResponse.newBuilder()
                .setDecision(Decision.newBuilder()
                        .setScore(0.85)
                        .setVerdictLabel("BLOCK")
                        .setVerdictExplanationMd("Block.")
                        .addContributingFactors(ContributingFactor.newBuilder()
                                .setName("graph_ring_detected")
                                .setWeight(0.8)
                                .setEvidence("ring"))
                        .setLatencyMs(240L)
                        .setJudgeProvider("anthropic")
                        .setJudgeModel("claude-haiku-4-5-20251001"))
                .build());

        Clock fixed = Clock.fixed(Instant.parse("2026-05-26T00:00:00Z"), ZoneOffset.UTC);
        DeliberationClient client = new DeliberationClient(channel, 5000L, fixed);

        DeliberationRequest request = DeliberationRequest.newBuilder()
                .setEventId("evt-1")
                .setDomain("fraud")
                .setBaselineEntityId("c")
                .addGraphEntityIds("c").addGraphEntityIds("d")
                .setEnrichedEventJson("{\"eventId\":\"evt-1\"}")
                .build();

        DecisionRecord record = client.deliberate(request);

        assertThat(record.eventId()).isEqualTo("evt-1");
        assertThat(record.domain()).isEqualTo("fraud");
        assertThat(record.score()).isEqualTo(0.85);
        assertThat(record.verdictLabel()).isEqualTo("BLOCK");
        assertThat(record.judgeProvider()).isEqualTo("anthropic");
        assertThat(record.judgeModel()).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(record.latencyMs()).isEqualTo(240L);
        assertThat(record.contributingFactors()).hasSize(1);
        assertThat(record.contributingFactors().getFirst().name()).isEqualTo("graph_ring_detected");
        // The client stamps a fresh UUID + createdAt from its injected clock.
        assertThat(record.decisionId()).isNotNull();
        assertThat(record.createdAt()).isEqualTo(Instant.parse("2026-05-26T00:00:00Z"));
        assertThat(record.enrichedEventJson()).isEqualTo("{\"eventId\":\"evt-1\"}");
    }

    @Test
    void server_internal_error_surfaces_as_status_runtime_exception() {
        servicer.errorOnce(Status.INTERNAL.withDescription("graph crashed"));
        DeliberationClient client = new DeliberationClient(channel);

        DeliberationRequest request = DeliberationRequest.newBuilder()
                .setEventId("e").setDomain("fraud").setBaselineEntityId("c")
                .setEnrichedEventJson("{}").build();

        assertThatThrownBy(() -> client.deliberate(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> assertThat(((StatusRuntimeException) t).getStatus().getCode())
                        .isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void timeout_surfaces_as_deadline_exceeded() throws Exception {
        // Servicer hangs forever; client deadline trips first.
        servicer.hangForever();
        DeliberationClient client = new DeliberationClient(channel, 100L, Clock.systemUTC());

        DeliberationRequest request = DeliberationRequest.newBuilder()
                .setEventId("e").setDomain("fraud").setBaselineEntityId("c")
                .setEnrichedEventJson("{}").build();

        assertThatThrownBy(() -> client.deliberate(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> assertThat(((StatusRuntimeException) t).getStatus().getCode())
                        .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
    }

    /** Minimal scripted judge servicer for the in-process channel. */
    private static final class TestDeliberationService
            extends DeliberationServiceGrpc.DeliberationServiceImplBase {
        private DeliberationResponse next;
        private Status nextError;
        private boolean hang;

        void responseOnce(DeliberationResponse response) { this.next = response; }
        void errorOnce(Status status) { this.nextError = status; }
        void hangForever() { this.hang = true; }

        @Override
        public void deliberate(DeliberationRequest request,
                               StreamObserver<DeliberationResponse> observer) {
            if (hang) return;  // never calls observer; deadline closes the call
            if (nextError != null) {
                observer.onError(nextError.asRuntimeException());
                nextError = null;
                return;
            }
            observer.onNext(next);
            observer.onCompleted();
            next = null;
        }
    }
}
