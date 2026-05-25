package io.conclave.graph.template.security;

import io.conclave.graph.domain.GraphFinding;
import io.conclave.graph.domain.GraphTemplate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

/**
 * Detects lateral-movement patterns: a single principal accessing an unusually large
 * number of distinct hosts. The classic compromised-account-then-scan signature.
 *
 * <p>Required param: {@code principalId}.
 *
 * <p>Attributes returned:
 * <ul>
 *   <li>{@code hostCount} (long) — distinct hosts the principal has accessed.</li>
 *   <li>{@code sampleHosts} (List&lt;String&gt;) — first 15 host IDs.</li>
 * </ul>
 *
 * <p>Risk signal: 0 below 5 hosts, otherwise {@code min(1.0, hostCount / 20.0)}.
 *
 * <p>Depth: 1 hop. Bounded.
 */
@Component
public class SecurityLateralMovementTemplate implements GraphTemplate {

    private static final String NAME = "security_lateral_movement";

    private static final String CYPHER = """
            MATCH (p:Principal {id: $principalId})-[:ACCESSED]->(h:Host)
            WITH p, count(DISTINCT h) AS hostCount,
                 collect(DISTINCT h.id)[..15] AS sampleHosts
            RETURN hostCount, sampleHosts
            """;

    @Override public String name()        { return NAME; }
    @Override public String domain()      { return "security"; }
    @Override public String description() {
        return "Distinct hosts a principal has accessed. High counts suggest "
             + "lateral movement after credential compromise.";
    }

    @Override
    public GraphFinding execute(Session session, Map<String, String> params) {
        String principalId = io.conclave.graph.template.Templates.required(params, "principalId");

        Result result = session.run(CYPHER, Map.of("principalId", principalId));
        if (!result.hasNext()) {
            return io.conclave.graph.template.Templates.empty(NAME, principalId, "security",
                    Map.of("hostCount", 0L, "sampleHosts", List.of()));
        }
        Record rec = result.next();
        long hostCount = rec.get("hostCount").asLong();
        List<String> hosts = rec.get("sampleHosts").asList(v -> v.asString());

        double risk = hostCount >= 5
                ? Math.min(1.0, hostCount / 20.0)
                : 0.0;

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("hostCount", hostCount);
        attrs.put("sampleHosts", hosts);

        return new GraphFinding(NAME, principalId, "security", attrs, risk, 0L);
    }
}
