package io.conclave.graph.ingest;

import org.apache.avro.generic.IndexedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Populates Neo4j from the enriched event stream so the M4 templates have a graph to
 * query. The graph service is domain-agnostic (it serves both fraud and security to
 * M5 at once), so this consumer subscribes to BOTH enriched topics and routes by the
 * Avro record type.
 *
 * <p>Asynchronous by design (spec decision): detection therefore has a real, measurable
 * latency — a ring/lateral campaign isn't visible until enough of its edges have landed.
 * That latency is a property the eval harness (Stage 4) reports, not a bug.
 *
 * <p>Gated by {@code conclave.graph.ingest.enabled} (default on) so the template ITs,
 * which seed Neo4j directly and run without a broker, can switch it off.
 */
@Component
@ConditionalOnProperty(name = "conclave.graph.ingest.enabled", havingValue = "true",
        matchIfMissing = true)
public class GraphIngestConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(GraphIngestConsumer.class);

    private static final String FRAUD_RECORD = "EnrichedPaymentEvent";
    private static final String SECURITY_RECORD = "EnrichedAuthEvent";

    private final GraphWriter writer;

    public GraphIngestConsumer(GraphWriter writer) {
        this.writer = writer;
    }

    @KafkaListener(
            topics = {"events.fraud.enriched", "events.security.enriched"},
            groupId = "${GRAPH_INGEST_GROUP_ID:conclave-graph-ingest}")
    public void onEnrichedEvent(IndexedRecord record) {
        if (record == null) {
            return;
        }
        String type = record.getSchema().getName();
        try {
            switch (type) {
                case FRAUD_RECORD -> writer.writeFraud(record);
                case SECURITY_RECORD -> writer.writeSecurity(record);
                default -> LOG.warn("graph ingest: unrecognized record type '{}'; skipping", type);
            }
        } catch (RuntimeException exc) {
            // Never let a single bad event stop the consumer; log + move on.
            LOG.error("graph ingest: failed to write {} to Neo4j", type, exc);
        }
    }
}
