package io.conclave.graph.storage;

import jakarta.annotation.PostConstruct;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Creates the indexes the templates rely on for sub-50ms p99 lookup. Idempotent —
 * {@code CREATE INDEX IF NOT EXISTS} is safe to run every startup, so a fresh
 * Testcontainer or a long-running prod instance both end up correctly indexed.
 *
 * <p>The label/property names below are the canonical schema documented in
 * ADR-003. Templates query these labels exclusively — if you add a new template,
 * also add its required index here.
 */
@Component
public class SchemaInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaInitializer.class);

    private final Driver driver;

    public SchemaInitializer(Driver driver) {
        this.driver = driver;
    }

    @PostConstruct
    public void init() {
        LOG.info("Initializing Neo4j schema (idempotent indexes)…");
        try (Session session = driver.session()) {
            // Fraud domain
            session.run("CREATE INDEX cardholder_id IF NOT EXISTS FOR (c:Cardholder) ON (c.id)");
            session.run("CREATE INDEX device_fingerprint IF NOT EXISTS FOR (d:Device) ON (d.fingerprint)");
            session.run("CREATE INDEX card_token IF NOT EXISTS FOR (c:Card) ON (c.token)");
            session.run("CREATE INDEX merchant_id IF NOT EXISTS FOR (m:Merchant) ON (m.id)");
            session.run("CREATE INDEX ip_address IF NOT EXISTS FOR (i:Ip) ON (i.address)");

            // Security domain
            session.run("CREATE INDEX principal_id IF NOT EXISTS FOR (p:Principal) ON (p.id)");
            session.run("CREATE INDEX host_id IF NOT EXISTS FOR (h:Host) ON (h.id)");
            session.run("CREATE INDEX resource_id IF NOT EXISTS FOR (r:Resource) ON (r.id)");
        }
        LOG.info("Neo4j schema ready.");
    }
}
