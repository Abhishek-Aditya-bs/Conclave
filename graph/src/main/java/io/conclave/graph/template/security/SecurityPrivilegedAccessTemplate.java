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
 * Surfaces a principal's access to sensitive resources. Any access to resources tagged
 * {@code high} or {@code restricted} produces a non-zero risk signal — the judge agent
 * decides whether the access is legitimate based on the rest of the evidence package.
 *
 * <p>Required param: {@code principalId}.
 *
 * <p>Attributes returned:
 * <ul>
 *   <li>{@code sensitiveCount} (long) — distinct sensitive resources accessed.</li>
 *   <li>{@code sampleResources} (List&lt;String&gt;) — first 10 resource IDs.</li>
 * </ul>
 *
 * <p>Risk signal: {@code 0.4 + 0.05 × sensitiveCount}, capped at 1.0.
 * Any sensitive access raises a flag; many accesses raise more.
 *
 * <p>Depth: 1 hop. Bounded.
 */
@Component
public class SecurityPrivilegedAccessTemplate implements GraphTemplate {

    private static final String NAME = "security_privileged_access";

    private static final String CYPHER = """
            MATCH (p:Principal {id: $principalId})-[:ACCESSED]->(r:Resource)
            WHERE r.sensitivity IN ['high', 'restricted']
            WITH p, count(DISTINCT r) AS sensitiveCount,
                 collect(DISTINCT r.id)[..10] AS sampleResources
            RETURN sensitiveCount, sampleResources
            """;

    @Override public String name()        { return NAME; }
    @Override public String domain()      { return "security"; }
    @Override public String description() {
        return "Sensitive (high/restricted) resources a principal has accessed. "
             + "Any access raises a flag; the judge agent decides legitimacy.";
    }

    @Override
    public GraphFinding execute(Session session, Map<String, String> params) {
        String principalId = io.conclave.graph.template.Templates.required(params, "principalId");

        Result result = session.run(CYPHER, Map.of("principalId", principalId));
        if (!result.hasNext()) {
            return io.conclave.graph.template.Templates.empty(NAME, principalId, "security",
                    Map.of("sensitiveCount", 0L, "sampleResources", List.of()));
        }
        Record rec = result.next();
        long sensitiveCount = rec.get("sensitiveCount").asLong();
        List<String> resources = rec.get("sampleResources").asList(v -> v.asString());

        double risk = sensitiveCount > 0
                ? Math.min(1.0, 0.4 + 0.05 * sensitiveCount)
                : 0.0;

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("sensitiveCount", sensitiveCount);
        attrs.put("sampleResources", resources);

        return new GraphFinding(NAME, principalId, "security", attrs, risk, 0L);
    }
}
