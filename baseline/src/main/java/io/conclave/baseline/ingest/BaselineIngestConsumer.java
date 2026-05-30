package io.conclave.baseline.ingest;

import io.conclave.baseline.service.BaselineService;
import org.apache.avro.generic.IndexedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Rolls each entity's behavioral baseline off the enriched event stream. The baseline
 * service is domain-agnostic, so this consumer subscribes to both enriched topics and
 * routes by Avro record type, keying the baseline on {@code baselineEntityId}
 * (cardholderId for fraud, principalId for security).
 *
 * <p>Asynchronous (spec decision): a brand-new entity is cold-start until enough of its
 * events have been folded in. Embedding each event (MiniLM, in-JVM) happens on the
 * listener thread — fine for the demo / paper volumes.
 *
 * <p>Gated by {@code conclave.baseline.ingest.enabled} (default on) so the existing ITs,
 * which exercise the service/REST/gRPC directly without a broker, can switch it off.
 */
@Component
@ConditionalOnProperty(name = "conclave.baseline.ingest.enabled", havingValue = "true",
        matchIfMissing = true)
public class BaselineIngestConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(BaselineIngestConsumer.class);

    private static final String FRAUD_RECORD = "EnrichedPaymentEvent";
    private static final String SECURITY_RECORD = "EnrichedAuthEvent";

    private final BaselineService service;

    public BaselineIngestConsumer(BaselineService service) {
        this.service = service;
    }

    @KafkaListener(
            topics = {"events.fraud.enriched", "events.security.enriched"},
            groupId = "${BASELINE_INGEST_GROUP_ID:conclave-baseline-ingest}")
    public void onEnrichedEvent(IndexedRecord record) {
        if (record == null) {
            return;
        }
        String type = record.getSchema().getName();
        try {
            String entityId = AvroFields.str(record, "baselineEntityId");
            if (entityId == null || entityId.isBlank()) {
                LOG.warn("baseline ingest: {} without baselineEntityId; skipping", type);
                return;
            }
            String domain = switch (type) {
                case FRAUD_RECORD -> "fraud";
                case SECURITY_RECORD -> "security";
                default -> null;
            };
            if (domain == null) {
                LOG.warn("baseline ingest: unrecognized record type '{}'; skipping", type);
                return;
            }
            String text = BaselineText.of(domain, name -> AvroFields.raw(record, name));
            service.update(domain, entityId, text);
        } catch (RuntimeException exc) {
            LOG.error("baseline ingest: failed to update baseline for {}", type, exc);
        }
    }
}
