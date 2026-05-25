package io.conclave.orchestrator.client;

import io.conclave.deliberation.proto.DeliberationRequest;
import io.conclave.deliberation.proto.DeliberationResponse;
import io.conclave.deliberation.proto.DeliberationServiceGrpc;
import io.conclave.orchestrator.domain.ContributingFactorRecord;
import io.conclave.orchestrator.domain.DecisionRecord;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Sync gRPC client for the M5 {@code DeliberationService}.
 *
 * <p>One channel per server lifetime — channels are thread-safe and pool
 * internally. The owning {@code @Configuration} class manages the channel
 * lifecycle; this client doesn't close it.
 *
 * <p>Per-call deadline defaults to 1500ms — that's the M5 600ms p99 target
 * plus headroom for network + a single retry slot the orchestrator hasn't
 * implemented yet. {@link StatusRuntimeException} propagates to the
 * caller; the orchestrator catches it and routes to the DLQ.
 */
public class DeliberationClient {

    private static final long DEFAULT_DEADLINE_MS = 1500L;

    private final DeliberationServiceGrpc.DeliberationServiceBlockingStub stub;
    private final long deadlineMs;
    private final Clock clock;

    public DeliberationClient(ManagedChannel channel) {
        this(channel, DEFAULT_DEADLINE_MS, Clock.systemUTC());
    }

    public DeliberationClient(ManagedChannel channel, long deadlineMs, Clock clock) {
        this.stub = DeliberationServiceGrpc.newBlockingStub(channel);
        this.deadlineMs = deadlineMs;
        this.clock = clock;
    }

    /**
     * Call M5 with the proto request; convert the response to a
     * {@link DecisionRecord} stamped with a fresh decision UUID and
     * the orchestrator's wall-clock {@code createdAt}.
     *
     * <p>The {@code latencyMs} field on the resulting record comes from
     * the M5 response (the server-side wallclock), NOT from the
     * orchestrator-side round-trip — that's what the audit dashboard
     * and the benchmark code both want. The orchestrator measures its
     * own latency separately.
     *
     * @throws StatusRuntimeException on any gRPC failure (timeout,
     *         server INTERNAL, unavailable). Caller decides DLQ vs retry.
     */
    public DecisionRecord deliberate(DeliberationRequest request) {
        DeliberationResponse response = stub
                .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .deliberate(request);
        return toRecord(request, response);
    }

    private DecisionRecord toRecord(DeliberationRequest request, DeliberationResponse response) {
        var proto = response.getDecision();
        List<ContributingFactorRecord> factors =
                new ArrayList<>(proto.getContributingFactorsCount());
        for (var pf : proto.getContributingFactorsList()) {
            factors.add(new ContributingFactorRecord(
                    pf.getName(),
                    pf.getWeight(),
                    pf.getEvidence()));
        }
        return new DecisionRecord(
                UUID.randomUUID(),
                request.getEventId(),
                request.getDomain(),
                proto.getScore(),
                proto.getVerdictLabel(),
                proto.getVerdictExplanationMd(),
                factors,
                proto.getLatencyMs(),
                proto.getJudgeProvider(),
                proto.getJudgeModel(),
                request.getEnrichedEventJson(),
                clock.instant());
    }
}
