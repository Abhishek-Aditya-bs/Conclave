package io.conclave.graph.domain;

import java.util.Map;
import org.neo4j.driver.Session;

/**
 * A fixed Cypher template that produces a structured {@link GraphFinding} for one
 * domain. Implementations are {@code @Component}s; the {@code GraphReasonerService}
 * autowires the full list and builds a name → template registry.
 *
 * <p>Every implementation MUST:
 * <ol>
 *   <li>use a fixed Cypher string (no string concatenation, params via {@code $name});</li>
 *   <li>bound path length explicitly ({@code *1..N}, never unbounded);</li>
 *   <li>return {@code GraphFinding(..., latencyMs = 0)} — the service stamps the
 *       real latency around the call.</li>
 * </ol>
 */
public interface GraphTemplate {

    /** Unique template identifier. Used as the lookup key in the registry. */
    String name();

    /** Domain this template serves: {@code "fraud"} or {@code "security"}. */
    String domain();

    /** Human-readable one-line description. Surfaced by {@code list_templates}. */
    String description();

    /**
     * Execute the template against the given session with the supplied params. All
     * params are strings; templates parse them as needed.
     *
     * @return a finding with {@code queryLatencyMs = 0} — the caller stamps the real
     *         value once the session closes.
     */
    GraphFinding execute(Session session, Map<String, String> params);
}
