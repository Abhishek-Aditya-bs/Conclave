package io.conclave.graph.template.fraud;

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
 * Detects card-testing rings: a single device used by many distinct cardholders to
 * own many distinct cards. Card-testing fraud bursts typically present this exact
 * pattern (one compromised device, many stolen/test cards).
 *
 * <p>Required param: {@code deviceFingerprint}.
 *
 * <p>Attributes returned:
 * <ul>
 *   <li>{@code cardholderCount} (long) — distinct cardholders touching this device.</li>
 *   <li>{@code cardCount} (long) — distinct cards owned by those cardholders.</li>
 *   <li>{@code sampleCardholders} (List&lt;String&gt;) — first 10 cardholder IDs.</li>
 * </ul>
 *
 * <p>Risk signal: 0 below 3 cardholders. At/above the 3-cardholder threshold a ring is
 * suspicious; risk ramps from 0.60 at 3 cardholders, crossing the 0.80 "confirmed ring"
 * mark at 4 and saturating at 1.0 by 5 distinct cardholders sharing one device. The
 * curve is count-driven (a real structural property of the graph), never inflated:
 * three or more different people transacting through one device is the defining
 * signature of a card-testing ring.
 *
 * <p>Depth: 2 hops (Device → Cardholder → Card). Bounded.
 */
@Component
public class FraudCardTestingRingTemplate implements GraphTemplate {

    private static final String NAME = "fraud_card_testing_ring";

    private static final String CYPHER = """
            MATCH (d:Device {fingerprint: $deviceFingerprint})<-[:USED_DEVICE]-(c:Cardholder)
            OPTIONAL MATCH (c)-[:OWNS]->(card:Card)
            WITH count(DISTINCT c)              AS cardholderCount,
                 count(DISTINCT card)           AS cardCount,
                 collect(DISTINCT c.id)[..10]   AS sampleCardholders
            RETURN cardholderCount, cardCount, sampleCardholders
            """;

    @Override public String name()        { return NAME; }
    @Override public String domain()      { return "fraud"; }
    @Override public String description() {
        return "Distinct cardholders + cards observed at a single device. "
             + "High counts suggest a card-testing ring.";
    }

    @Override
    public GraphFinding execute(Session session, Map<String, String> params) {
        String deviceFp = io.conclave.graph.template.Templates.required(params, "deviceFingerprint");

        Result result = session.run(CYPHER, Map.of("deviceFingerprint", deviceFp));
        if (!result.hasNext()) {
            return io.conclave.graph.template.Templates.empty(NAME, deviceFp, "fraud",
                    Map.of("cardholderCount", 0L,
                           "cardCount", 0L,
                           "sampleCardholders", List.of()));
        }
        Record rec = result.next();
        long cardholderCount = rec.get("cardholderCount").asLong();
        long cardCount       = rec.get("cardCount").asLong();
        List<String> sample  = rec.get("sampleCardholders")
                .asList(v -> v.asString());

        double risk = riskFor(cardholderCount);

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("cardholderCount", cardholderCount);
        attrs.put("cardCount", cardCount);
        attrs.put("sampleCardholders", sample);

        return new GraphFinding(NAME, deviceFp, "fraud", attrs, risk, 0L);
    }

    /**
     * Count-driven risk curve. Below 3 distinct cardholders on one device there is no
     * ring (risk 0). At the 3-cardholder threshold the ring is suspicious (0.60); risk
     * then ramps to 0.80 at 4 cardholders ("confirmed ring", BLOCK-grade) and saturates
     * at 1.0 by 5. This reflects a real structural property of the graph — it is never
     * inflated beyond what the cardholder count supports.
     */
    static double riskFor(long cardholderCount) {
        if (cardholderCount < 3) {
            return 0.0;
        }
        double ramp = 0.60 + 0.20 * (cardholderCount - 3);
        return Math.min(1.0, ramp);
    }
}
