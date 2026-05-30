package io.conclave.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conclave.orchestrator.domain.ContributingFactorRecord;
import io.conclave.orchestrator.domain.DecisionRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JdbcTemplate-backed read implementation. Two row mappers:
 * <ul>
 *   <li>{@link #DETAIL_MAPPER} reconstructs the full {@link DecisionRecord},
 *       parsing the JSONB factors back into typed records.</li>
 *   <li>{@link #SUMMARY_MAPPER} pulls just the columns the list endpoint
 *       needs — no JSONB parsing, faster.</li>
 * </ul>
 *
 * <p>Filter assembly is hand-rolled (we don't pull in jOOQ for two screens
 * worth of SQL); see {@link #appendWhere} for the predicate matrix.
 *
 * <p>Gated behind {@code conclave.orchestrator.enabled} for the same
 * reason as the orchestrator components (so the ingest/stream ITs that skip Postgres
 * still boot cleanly).
 */
@Repository
@ConditionalOnProperty(name = "conclave.orchestrator.enabled", havingValue = "true",
        matchIfMissing = true)
public class JdbcDecisionAuditRepository implements DecisionAuditRepository {

    private static final String SUMMARY_COLUMNS =
            "decision_id, event_id, domain, baseline_entity_id, score, verdict_label, "
                    + "latency_ms, judge_provider, judge_model, created_at";

    private static final String DETAIL_COLUMNS =
            "decision_id, event_id, domain, baseline_entity_id, score, verdict_label, "
                    + "verdict_explanation_md, contributing_factors::text AS factors_json, "
                    + "latency_ms, judge_provider, judge_model, enriched_event_json, created_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JdbcDecisionAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- findById ----------

    @Override
    public Optional<DecisionRecord> findById(UUID decisionId) {
        try {
            DecisionRecord r = jdbc.queryForObject(
                    "SELECT " + DETAIL_COLUMNS + " FROM decisions WHERE decision_id = ?",
                    detailMapper(),
                    decisionId);
            return Optional.ofNullable(r);
        } catch (EmptyResultDataAccessException notFound) {
            return Optional.empty();
        }
    }

    // ---------- findAll ----------

    @Override
    public List<DecisionSummary> findAll(DecisionFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(SUMMARY_COLUMNS)
                .append(" FROM decisions");
        List<Object> args = new ArrayList<>();
        appendWhere(filter, sql, args);
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(filter.limit());
        args.add(filter.offset());
        return jdbc.query(sql.toString(), summaryMapper(), args.toArray());
    }

    @Override
    public long count(DecisionFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM decisions");
        List<Object> args = new ArrayList<>();
        appendWhere(filter, sql, args);
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    /**
     * Appends WHERE clauses + their bind args. Mutates both inputs.
     * Each predicate emits exactly one {@code ?} placeholder so positional
     * args line up with the SQL string.
     */
    private static void appendWhere(DecisionFilter filter, StringBuilder sql, List<Object> args) {
        List<String> clauses = new ArrayList<>();
        filter.domain().ifPresent(v -> {
            clauses.add("domain = ?");
            args.add(v);
        });
        filter.verdictLabel().ifPresent(v -> {
            clauses.add("verdict_label = ?");
            args.add(v);
        });
        filter.baselineEntityId().ifPresent(v -> {
            clauses.add("baseline_entity_id = ?");
            args.add(v);
        });
        filter.minScore().ifPresent(v -> {
            clauses.add("score >= ?");
            args.add(v);
        });
        filter.maxScore().ifPresent(v -> {
            clauses.add("score <= ?");
            args.add(v);
        });
        filter.since().ifPresent(v -> {
            clauses.add("created_at >= ?");
            args.add(Timestamp.from(v));
        });
        filter.until().ifPresent(v -> {
            clauses.add("created_at < ?");
            args.add(Timestamp.from(v));
        });
        filter.judgeProvider().ifPresent(v -> {
            clauses.add("judge_provider = ?");
            args.add(v);
        });
        if (!clauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", clauses));
        }
    }

    // ---------- mappers ----------

    private RowMapper<DecisionSummary> summaryMapper() {
        return (rs, idx) -> new DecisionSummary(
                rs.getObject("decision_id", UUID.class),
                rs.getString("event_id"),
                rs.getString("domain"),
                rs.getString("baseline_entity_id"),
                rs.getDouble("score"),
                rs.getString("verdict_label"),
                rs.getLong("latency_ms"),
                rs.getString("judge_provider"),
                rs.getString("judge_model"),
                rs.getTimestamp("created_at").toInstant());
    }

    private RowMapper<DecisionRecord> detailMapper() {
        return (rs, idx) -> {
            List<ContributingFactorRecord> factors = parseFactors(rs.getString("factors_json"));
            return new DecisionRecord(
                    rs.getObject("decision_id", UUID.class),
                    rs.getString("event_id"),
                    rs.getString("domain"),
                    rs.getString("baseline_entity_id"),
                    rs.getDouble("score"),
                    rs.getString("verdict_label"),
                    rs.getString("verdict_explanation_md"),
                    factors,
                    rs.getLong("latency_ms"),
                    rs.getString("judge_provider"),
                    rs.getString("judge_model"),
                    rs.getString("enriched_event_json"),
                    instantOf(rs, "created_at"));
        };
    }

    private static Instant instantOf(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private List<ContributingFactorRecord> parseFactors(String factorsJson) {
        if (factorsJson == null || factorsJson.isBlank()) {
            return List.of();
        }
        try {
            List<?> root = objectMapper.readValue(factorsJson, List.class);
            List<ContributingFactorRecord> out = new ArrayList<>(root.size());
            for (Object element : root) {
                if (!(element instanceof java.util.Map<?, ?> map)) {
                    continue;
                }
                String name = String.valueOf(map.get("name"));
                Number weight = (Number) map.get("weight");
                String evidence = String.valueOf(map.get("evidence"));
                out.add(new ContributingFactorRecord(
                        name,
                        weight == null ? 0.0 : weight.doubleValue(),
                        evidence == null ? "" : evidence));
            }
            return out;
        } catch (JsonProcessingException e) {
            // The orchestrator only inserts well-formed JSON, so unparseable
            // here means hand-tampered data; surface as an unchecked exception.
            throw new IllegalStateException(
                    "Could not parse contributing_factors JSON: " + factorsJson, e);
        }
    }
}
