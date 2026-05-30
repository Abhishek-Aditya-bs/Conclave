package io.conclave.graph.domain;

import java.util.Map;
import java.util.Objects;

/**
 * Structured payload returned by every {@link GraphTemplate}. The judge agent
 * consumes findings as one input among several when scoring an event.
 *
 * @param templateName   the {@link GraphTemplate#name()} that produced this finding.
 * @param rootEntityId   the entity the query was rooted on (cardholder/principal/etc).
 * @param domain         {@code "fraud"} or {@code "security"}.
 * @param attributes     template-specific structured data. Each template documents
 *                       its keys; consumers introspect by template name.
 * @param riskSignal     normalized {@code [0, 1]} risk hint derived from the query
 *                       result. NOT a verdict — the judge can override or ignore.
 * @param queryLatencyMs wall-clock ms the underlying Cypher execution took.
 *                       Stamped by {@code GraphReasonerService} after the call.
 */
public record GraphFinding(
        String templateName,
        String rootEntityId,
        String domain,
        Map<String, Object> attributes,
        double riskSignal,
        long queryLatencyMs) {

    public GraphFinding {
        Objects.requireNonNull(templateName, "templateName");
        Objects.requireNonNull(rootEntityId, "rootEntityId");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(attributes, "attributes");
        if (riskSignal < 0.0 || riskSignal > 1.0) {
            throw new IllegalArgumentException(
                    "riskSignal must be in [0, 1], got " + riskSignal);
        }
        if (queryLatencyMs < 0) {
            throw new IllegalArgumentException(
                    "queryLatencyMs must be >= 0, got " + queryLatencyMs);
        }
    }

    /** Return a copy with a new latency value (templates emit 0, service stamps it). */
    public GraphFinding withLatency(long ms) {
        return new GraphFinding(templateName, rootEntityId, domain, attributes, riskSignal, ms);
    }
}
