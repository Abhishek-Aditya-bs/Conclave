package io.conclave.graph.template.fraud;

import io.conclave.graph.domain.GraphFinding;
import io.conclave.graph.domain.GraphTemplate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Component;

/**
 * Returns the 1-2 hop neighborhood of a cardholder — the device/IP/card/merchant
 * graph nodes within two relationships. Pure context for the judge: no direct risk
 * signal, just "what does this cardholder's graph look like."
 *
 * <p>Required param: {@code cardholderId}.
 *
 * <p>Attributes returned:
 * <ul>
 *   <li>{@code neighborCount} (long) — distinct neighbors within 2 hops, capped at 200
 *       internally to keep latency bounded.</li>
 *   <li>{@code sample} (List&lt;Map&gt;) — up to 15 neighbors as {@code {label, id}}.</li>
 * </ul>
 *
 * <p>Risk signal: always 0.0 — this is descriptive context, not a verdict.
 *
 * <p>Depth: 2 hops, capped via {@code *1..2}. Bounded.
 */
@Component
public class FraudCardholderNeighborhoodTemplate implements GraphTemplate {

    private static final String NAME = "fraud_cardholder_neighborhood";

    private static final String CYPHER = """
            MATCH (c:Cardholder {id: $cardholderId})-[*1..2]-(n)
            WHERE n <> c
            WITH c, collect(DISTINCT n)[..200] AS neighbors
            RETURN size(neighbors) AS neighborCount,
                   [x IN neighbors[..15] |
                      {label: labels(x)[0],
                       id: coalesce(x.id, x.fingerprint, x.address, x.token, '<unknown>')}
                   ] AS sample
            """;

    @Override public String name()        { return NAME; }
    @Override public String domain()      { return "fraud"; }
    @Override public String description() {
        return "1-2 hop neighborhood of a cardholder (devices, IPs, cards, merchants). "
             + "Descriptive context for the judge; not itself a risk signal.";
    }

    @Override
    public GraphFinding execute(Session session, Map<String, String> params) {
        String cardholderId = io.conclave.graph.template.Templates.required(params, "cardholderId");

        Result result = session.run(CYPHER, Map.of("cardholderId", cardholderId));
        if (!result.hasNext()) {
            return io.conclave.graph.template.Templates.empty(NAME, cardholderId, "fraud",
                    Map.of("neighborCount", 0L, "sample", List.of()));
        }
        Record rec = result.next();
        long neighborCount = rec.get("neighborCount").asLong();
        List<Map<String, Object>> sample = rec.get("sample").asList(Value::asMap);

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("neighborCount", neighborCount);
        attrs.put("sample", sample);

        return new GraphFinding(NAME, cardholderId, "fraud", attrs, 0.0, 0L);
    }
}
