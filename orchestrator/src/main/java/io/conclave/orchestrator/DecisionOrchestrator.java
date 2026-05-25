package io.conclave.orchestrator;

import io.conclave.deliberation.proto.DeliberationRequest;
import io.conclave.ingest.IngestProperties;
import io.conclave.orchestrator.client.DeliberationClient;
import io.conclave.orchestrator.domain.DecisionRecord;
import io.conclave.orchestrator.encode.DeliberationRequestTranslator;
import io.conclave.orchestrator.messaging.DecisionPublisher;
import io.conclave.orchestrator.messaging.DlqPublisher;
import io.conclave.orchestrator.messaging.DlqPublisher.FailureReason;
import io.conclave.orchestrator.storage.DecisionRepository;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.apache.avro.generic.IndexedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * End-to-end workflow per enriched event:
 *
 * <pre>
 * 1. translate enriched-event Avro → M5 DeliberationRequest (JSON in proto)
 * 2. call M5 over gRPC, get a Decision
 * 3. persist the Decision to Postgres
 * 4. emit the Decision on decisions.{domain}
 * </pre>
 *
 * <p>Every error category routes to the DLQ instead of bubbling up and
 * blocking the consumer thread:
 * <ul>
 *   <li>Translation errors (malformed enriched event) →
 *       {@link FailureReason#TRANSLATION_FAILURE}.</li>
 *   <li>M5 timeout (gRPC {@code DEADLINE_EXCEEDED}) →
 *       {@link FailureReason#M5_TIMEOUT}.</li>
 *   <li>M5 unreachable ({@code UNAVAILABLE}) → {@link FailureReason#M5_UNAVAILABLE}.</li>
 *   <li>M5 internal error → {@link FailureReason#M5_INTERNAL}.</li>
 *   <li>Postgres write failure → {@link FailureReason#PERSISTENCE_FAILURE}.
 *       At this point the Decision has been produced but not stored — we
 *       still emit on {@code decisions.{domain}} for downstream consumers
 *       AND route to DLQ so the audit replay can backfill the row.</li>
 *   <li>Anything else → {@link FailureReason#UNEXPECTED}.</li>
 * </ul>
 *
 * <p>Tracking the post-persist Kafka emit failure case is intentionally
 * out of scope: we trust the producer's idempotent+acks=all config
 * (see application.yaml). A persistent Kafka outage would manifest
 * upstream as the listener failing to commit offsets and is handled by
 * Kafka's retry/rebalance machinery.
 */
@Service
@ConditionalOnProperty(name = "conclave.orchestrator.enabled", havingValue = "true",
        matchIfMissing = true)
public class DecisionOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionOrchestrator.class);

    private final DeliberationRequestTranslator translator;
    private final DeliberationClient deliberationClient;
    private final DecisionRepository decisionRepository;
    private final DecisionPublisher decisionPublisher;
    private final DlqPublisher dlqPublisher;
    private final IngestProperties ingestProperties;

    public DecisionOrchestrator(
            DeliberationRequestTranslator translator,
            DeliberationClient deliberationClient,
            DecisionRepository decisionRepository,
            DecisionPublisher decisionPublisher,
            DlqPublisher dlqPublisher,
            IngestProperties ingestProperties) {
        this.translator = translator;
        this.deliberationClient = deliberationClient;
        this.decisionRepository = decisionRepository;
        this.decisionPublisher = decisionPublisher;
        this.dlqPublisher = dlqPublisher;
        this.ingestProperties = ingestProperties;
    }

    public void process(IndexedRecord enriched) {
        // 1. Translate.
        DeliberationRequest request;
        try {
            request = translator.toRequest(ingestProperties.domain(), enriched);
        } catch (RuntimeException exc) {
            String eventId = bestEffortEventId(enriched);
            LOG.warn("Translation failed for event {}: {}", eventId, exc.getMessage());
            dlqPublisher.publish(eventId, FailureReason.TRANSLATION_FAILURE,
                    exc.getMessage(), "");
            return;
        }

        // 2. Call M5.
        DecisionRecord decision;
        try {
            decision = deliberationClient.deliberate(request);
        } catch (StatusRuntimeException exc) {
            FailureReason reason = classify(exc.getStatus());
            LOG.warn("M5 call failed for event {} (status={}): {}",
                    request.getEventId(), exc.getStatus().getCode(), exc.getMessage());
            dlqPublisher.publish(request.getEventId(), reason,
                    exc.getStatus().getCode() + ": " + exc.getMessage(),
                    request.getEnrichedEventJson());
            return;
        } catch (RuntimeException exc) {
            LOG.error("Unexpected error calling M5 for event {}",
                    request.getEventId(), exc);
            dlqPublisher.publish(request.getEventId(), FailureReason.UNEXPECTED,
                    exc.getClass().getSimpleName() + ": " + exc.getMessage(),
                    request.getEnrichedEventJson());
            return;
        }

        // 3. Persist. On failure we STILL emit on decisions.{domain} (downstream
        //    consumers should not be starved by a transient DB outage) but ALSO
        //    DLQ so the audit replay can backfill the row when DB recovers.
        boolean persisted = true;
        try {
            decisionRepository.save(decision);
        } catch (RuntimeException exc) {
            persisted = false;
            LOG.error("Persistence failed for decision {} (event {})",
                    decision.decisionId(), decision.eventId(), exc);
            dlqPublisher.publish(decision.eventId(), FailureReason.PERSISTENCE_FAILURE,
                    exc.getClass().getSimpleName() + ": " + exc.getMessage(),
                    request.getEnrichedEventJson());
        }

        // 4. Emit decisions.{domain}. Producer is idempotent + acks=all so a
        //    transient broker hiccup retries internally.
        try {
            decisionPublisher.publish(decision);
        } catch (RuntimeException exc) {
            // If publishing fails AFTER successful persist, we have a decision
            // row in Postgres but no downstream notification. DLQ records that
            // so a replay job can re-emit.
            LOG.error("Failed to emit decision {} on decisions.{}",
                    decision.decisionId(), ingestProperties.domain().key(), exc);
            dlqPublisher.publish(decision.eventId(), FailureReason.UNEXPECTED,
                    "publish failed (persisted=" + persisted + "): " + exc.getMessage(),
                    request.getEnrichedEventJson());
        }
    }

    /** Map gRPC status codes onto the DLQ reason vocabulary. */
    static FailureReason classify(Status status) {
        Status.Code code = status.getCode();
        if (code == Status.Code.DEADLINE_EXCEEDED) {
            return FailureReason.M5_TIMEOUT;
        }
        if (code == Status.Code.UNAVAILABLE) {
            return FailureReason.M5_UNAVAILABLE;
        }
        if (code == Status.Code.INTERNAL) {
            return FailureReason.M5_INTERNAL;
        }
        // Other codes (INVALID_ARGUMENT, UNIMPLEMENTED, ...) all mean the
        // request itself was bad — bucket as M5_INTERNAL so alerts fire on
        // anything non-timeout/non-availability.
        return FailureReason.M5_INTERNAL;
    }

    /**
     * The translator extracts {@code eventId} fail-fast; if we couldn't
     * even translate the record we still want SOMETHING in the DLQ key so
     * the replay tool can group failures.
     */
    private static String bestEffortEventId(IndexedRecord enriched) {
        try {
            var field = enriched.getSchema().getField("eventId");
            if (field != null) {
                Object value = enriched.get(field.pos());
                if (value != null) {
                    return value.toString();
                }
            }
        } catch (RuntimeException ignored) {
            // Best-effort; never fail the caller because of this.
        }
        return "<unknown>";
    }
}
