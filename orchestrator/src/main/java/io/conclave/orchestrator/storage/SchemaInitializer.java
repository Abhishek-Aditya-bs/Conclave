package io.conclave.orchestrator.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the {@code decisions} table on startup. Mirrors the idempotent
 * {@code CREATE TABLE IF NOT EXISTS} pattern the baseline service uses for {@code baselines}.
 *
 * <p>Schema choices (recorded in ADR-005):
 * <ul>
 *   <li>{@code decision_id UUID PRIMARY KEY} — generated on the orchestrator
 *       so the same row can be referenced across the audit API and
 *       downstream consumers; not a Postgres-side default to keep the
 *       application portable.</li>
 *   <li>{@code contributing_factors JSONB} — opaque to the relational
 *       schema, but JSONB lets the audit API query by factor name /
 *       weight without forcing a normalized child table.</li>
 *   <li>{@code enriched_event_json TEXT} — verbatim of what the orchestrator sent to the judge.
 *       Persisting the prompt input is what makes the replay endpoint
 *       cheap.</li>
 *   <li>{@code created_at TIMESTAMP WITH TIME ZONE} — clock skew protection
 *       on cross-region deployments. Defaults to NOW() server-side as a
 *       sanity backstop; the application always sends its own value.</li>
 * </ul>
 *
 * <p>Three indexes: by {@code event_id} (audit lookup), by
 * {@code (domain, score DESC)} (dashboard ranking), by
 * {@code created_at DESC} (recent-decisions feed).
 */
/*
 * Gated behind {@code conclave.orchestrator.enabled} (default true) so the
 * older ingest/stream ITs that don't spin up a Postgres container can disable the
 * decision-orchestrator slice cleanly. Same flag covers
 * {@link io.conclave.orchestrator.JdbcDecisionRepository},
 * {@link io.conclave.orchestrator.DecisionConsumer}, and the orchestrator's messaging
 * config classes — disabling the flag drops the full orchestrator graph as one unit.
 */
@Component
@ConditionalOnProperty(name = "conclave.orchestrator.enabled", havingValue = "true",
        matchIfMissing = true)
public class SchemaInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaInitializer.class);

    // Idempotent. Run on every startup so a fresh Postgres + the orchestrator
    // converge without a separate migration tool. Flyway is overkill for one
    // table; we'll revisit if the audit schema gets more complex.
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS decisions (
                decision_id            UUID PRIMARY KEY,
                event_id               VARCHAR(128) NOT NULL,
                domain                 VARCHAR(16)  NOT NULL,
                baseline_entity_id     VARCHAR(128) NOT NULL,
                score                  DOUBLE PRECISION NOT NULL,
                verdict_label          VARCHAR(32)  NOT NULL,
                verdict_explanation_md TEXT NOT NULL,
                contributing_factors   JSONB NOT NULL,
                latency_ms             BIGINT NOT NULL,
                judge_provider         VARCHAR(32)  NOT NULL,
                judge_model            VARCHAR(64)  NOT NULL,
                enriched_event_json    TEXT NOT NULL,
                created_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
            )
            """;

    // Idempotent migration step: older tables don't have
    // baseline_entity_id. ADD COLUMN IF NOT EXISTS is Postgres 9.6+; we
    // rely on it for the demo cluster's roll-forward and skip Flyway/Liquibase.
    private static final String ENSURE_BASELINE_ENTITY_COLUMN = """
            ALTER TABLE decisions
                ADD COLUMN IF NOT EXISTS baseline_entity_id VARCHAR(128) NOT NULL DEFAULT ''
            """;

    private static final String[] CREATE_INDEXES = {
            "CREATE INDEX IF NOT EXISTS idx_decisions_event_id          ON decisions (event_id)",
            "CREATE INDEX IF NOT EXISTS idx_decisions_domain_score      ON decisions (domain, score DESC)",
            "CREATE INDEX IF NOT EXISTS idx_decisions_created_at        ON decisions (created_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_decisions_baseline_entity   ON decisions (baseline_entity_id)",
    };

    private final JdbcTemplate jdbc;

    public SchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        LOG.info("Initializing decisions table schema");
        jdbc.execute(CREATE_TABLE);
        // Roll-forward: older tables don't carry baseline_entity_id; this
        // ALTER is idempotent so a fresh table just no-ops.
        jdbc.execute(ENSURE_BASELINE_ENTITY_COLUMN);
        for (String ddl : CREATE_INDEXES) {
            jdbc.execute(ddl);
        }
        LOG.info("decisions table ready");
    }
}
