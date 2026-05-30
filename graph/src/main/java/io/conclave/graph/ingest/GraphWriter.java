package io.conclave.graph.ingest;

import java.util.HashMap;
import java.util.Map;
import org.apache.avro.generic.IndexedRecord;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Upserts the nodes + edges the Cypher templates query, one enriched event at a time.
 * All writes are {@code MERGE} (idempotent on the node keys), so replaying the stream
 * or re-consuming after a rebalance never duplicates structure. Because the templates
 * count with {@code count(DISTINCT …)}, a re-MERGEd edge can't inflate a fan-out either.
 *
 * <p>Edge shapes are exactly what the templates traverse:
 * <ul>
 *   <li>Fraud: {@code (Cardholder)-[:USED_DEVICE]->(Device)}, {@code -[:OWNS]->(Card)},
 *       {@code -[:FROM_IP]->(Ip)}, {@code -[:AT_MERCHANT]->(Merchant)}.</li>
 *   <li>Security: {@code (Principal)-[:ACCESSED]->(Host)} and, when a target resource
 *       is present, {@code (Principal)-[:ACCESSED]->(Resource{sensitivity})}.</li>
 * </ul>
 */
@Component
public class GraphWriter {

    private static final Logger LOG = LoggerFactory.getLogger(GraphWriter.class);

    private static final String FRAUD_CYPHER = """
            MERGE (c:Cardholder {id: $cardholderId})
            MERGE (d:Device {fingerprint: $deviceFingerprint})
            MERGE (i:Ip {address: $ipAddress})
            MERGE (m:Merchant {id: $merchantId})
            MERGE (card:Card {token: $cardToken})
            MERGE (c)-[:USED_DEVICE]->(d)
            MERGE (c)-[:FROM_IP]->(i)
            MERGE (c)-[:AT_MERCHANT]->(m)
            MERGE (c)-[:OWNS]->(card)
            """;

    private static final String SECURITY_HOST_CYPHER = """
            MERGE (p:Principal {id: $principalId})
            MERGE (h:Host {id: $hostId})
            MERGE (p)-[:ACCESSED]->(h)
            """;

    private static final String SECURITY_RESOURCE_CYPHER = """
            MERGE (p:Principal {id: $principalId})
            MERGE (r:Resource {id: $targetResource})
            SET r.sensitivity = $sensitivity
            MERGE (p)-[:ACCESSED]->(r)
            """;

    private final Driver driver;

    public GraphWriter(Driver driver) {
        this.driver = driver;
    }

    /** Upsert one EnrichedPaymentEvent into the fraud graph. */
    public void writeFraud(IndexedRecord event) {
        String cardholder = AvroFields.str(event, "cardholderId");
        if (cardholder == null) {
            LOG.warn("graph ingest (fraud): event without cardholderId; skipping");
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("cardholderId", cardholder);
        params.put("deviceFingerprint", AvroFields.str(event, "deviceFingerprint"));
        params.put("ipAddress", AvroFields.str(event, "ipAddress"));
        params.put("merchantId", AvroFields.str(event, "merchantId"));
        params.put("cardToken", AvroFields.str(event, "cardToken"));
        run(FRAUD_CYPHER, params);
    }

    /** Upsert one EnrichedAuthEvent into the security graph. */
    public void writeSecurity(IndexedRecord event) {
        String principal = AvroFields.str(event, "principalId");
        String host = AvroFields.str(event, "hostId");
        if (principal == null || host == null) {
            LOG.warn("graph ingest (security): event missing principalId/hostId; skipping");
            return;
        }
        Map<String, Object> hostParams = new HashMap<>();
        hostParams.put("principalId", principal);
        hostParams.put("hostId", host);
        run(SECURITY_HOST_CYPHER, hostParams);

        String resource = AvroFields.str(event, "targetResource");
        if (resource != null && !resource.isBlank()) {
            Map<String, Object> resParams = new HashMap<>();
            resParams.put("principalId", principal);
            resParams.put("targetResource", resource);
            resParams.put("sensitivity", ResourceSensitivity.classify(resource));
            run(SECURITY_RESOURCE_CYPHER, resParams);
        }
    }

    private void run(String cypher, Map<String, Object> params) {
        try (Session session = driver.session()) {
            session.run(cypher, params).consume();
        }
    }
}
