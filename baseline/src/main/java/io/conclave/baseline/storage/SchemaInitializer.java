package io.conclave.baseline.storage;

import io.conclave.baseline.config.BaselineProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotent schema bootstrap. Runs once at startup, before any beans that depend on
 * the {@code baselines} table.
 *
 * <p>Flyway / Liquibase would be the production choice, but for the demo a single
 * {@code CREATE TABLE IF NOT EXISTS} keeps the deployment surface tiny and lets a fresh
 * Testcontainer start clean every run.
 */
@Component
public class SchemaInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaInitializer.class);

    private final JdbcTemplate jdbc;
    private final int embeddingDim;

    public SchemaInitializer(JdbcTemplate jdbc, BaselineProperties properties) {
        this.jdbc = jdbc;
        this.embeddingDim = properties.embeddingDim();
    }

    @PostConstruct
    public void init() {
        LOG.info("Initializing baselines schema (vector dimension = {})", embeddingDim);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS baselines ("
              + "  entity_id    TEXT        NOT NULL,"
              + "  domain       TEXT        NOT NULL,"
              + "  embedding    vector(" + embeddingDim + ") NOT NULL,"
              + "  event_count  BIGINT      NOT NULL,"
              + "  last_updated TIMESTAMPTZ NOT NULL,"
              + "  PRIMARY KEY (entity_id, domain)"
              + ")");
        // Index on (domain, entity_id) is already the PK; no secondary index needed
        // until we add similarity-search queries (deferred to a future module).
    }
}
