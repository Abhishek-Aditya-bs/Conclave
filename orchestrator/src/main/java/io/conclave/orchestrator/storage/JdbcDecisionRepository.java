package io.conclave.orchestrator.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conclave.orchestrator.domain.ContributingFactorRecord;
import io.conclave.orchestrator.domain.DecisionRecord;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JdbcTemplate-backed write path for {@link DecisionRecord}.
 *
 * <p>Two implementation notes worth tracking:
 * <ol>
 *   <li>{@code contributing_factors} is JSONB on the Postgres side; the
 *       JDBC driver expects {@code Object}-typed parameters with explicit
 *       cast on the SQL side ({@code ?::jsonb}). Same pattern as M3's
 *       pgvector text-to-vector cast.</li>
 *   <li>{@code ObjectMapper} is instantiated directly (not autowired)
 *       because Spring Boot 4 with only {@code spring-boot-starter-web}
 *       on the classpath doesn't auto-register one in every layout — M4
 *       hit the same gotcha. ObjectMapper is thread-safe; one per repo
 *       is fine.</li>
 * </ol>
 */
@Repository
@ConditionalOnProperty(name = "conclave.orchestrator.enabled", havingValue = "true",
        matchIfMissing = true)
public class JdbcDecisionRepository implements DecisionRepository {

    // Column order must match the parameter order in JdbcTemplate.update(...) below.
    private static final String INSERT = """
            INSERT INTO decisions (
                decision_id, event_id, domain, baseline_entity_id, score,
                verdict_label, verdict_explanation_md, contributing_factors,
                latency_ms, judge_provider, judge_model, enriched_event_json,
                created_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JdbcDecisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(DecisionRecord decision) {
        String factorsJson = serializeFactors(decision.contributingFactors());
        jdbc.update(
                INSERT,
                decision.decisionId(),
                decision.eventId(),
                decision.domain(),
                decision.baselineEntityId(),
                decision.score(),
                decision.verdictLabel(),
                decision.verdictExplanationMd(),
                factorsJson,
                decision.latencyMs(),
                decision.judgeProvider(),
                decision.judgeModel(),
                decision.enrichedEventJson(),
                Timestamp.from(decision.createdAt()));
    }

    private String serializeFactors(List<ContributingFactorRecord> factors) {
        List<Map<String, Object>> payload = new ArrayList<>(factors.size());
        for (ContributingFactorRecord f : factors) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", f.name());
            map.put("weight", f.weight());
            map.put("evidence", f.evidence());
            payload.add(map);
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Records only carry primitive + String — a serialization failure
            // would indicate a JVM-level Jackson misconfiguration, not data.
            throw new IllegalStateException(
                    "Failed to serialize contributing_factors for decision "
                            + decision_id_safe(factors), e);
        }
    }

    // Defensive helper purely to keep the error message useful without
    // pulling more state into the catch block.
    private static String decision_id_safe(List<ContributingFactorRecord> factors) {
        return factors.isEmpty() ? "<no factors>" : factors.getFirst().name();
    }
}
