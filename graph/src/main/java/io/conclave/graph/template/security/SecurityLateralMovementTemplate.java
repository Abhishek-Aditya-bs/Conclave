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
 * <p>Risk signal: 0 below 5 hosts. At/above the 5-host threshold a fan-out is
 * suspicious; risk ramps from 0.60 at 5 hosts, crossing the 0.80 "confirmed lateral
 * movement" mark at 6 distinct hosts and saturating at 1.0 by 7. The curve is
 * count-driven (a real structural property of the graph), never inflated.
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

        double risk = riskFor(hostCount);

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("hostCount", hostCount);
        attrs.put("sampleHosts", hosts);

        return new GraphFinding(NAME, principalId, "security", attrs, risk, 0L);
    }

    /**
     * Count-driven risk curve. Below 5 distinct hosts there is no lateral-movement
     * signature (risk 0). At the 5-host threshold the fan-out is suspicious (0.60); risk
     * then ramps to 0.80 at 6 hosts ("confirmed lateral movement", BLOCK-grade) and
     * saturates at 1.0 by 7. Reflects a real structural property of the graph — never
     * inflated beyond what the host count supports.
     */
    static double riskFor(long hostCount) {
        if (hostCount < 5) {
            return 0.0;
        }
        double ramp = 0.60 + 0.20 * (hostCount - 5);
        return Math.min(1.0, ramp);
    }
}
