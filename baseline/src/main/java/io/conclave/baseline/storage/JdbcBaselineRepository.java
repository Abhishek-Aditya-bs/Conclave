package io.conclave.baseline.storage;

import io.conclave.baseline.domain.Baseline;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Postgres + pgvector storage for {@link Baseline}s. Uses raw {@link JdbcTemplate} — no
 * JPA, no Hibernate — because the vector column is a custom type and the row shape is
 * small enough that the abstraction cost outweighs the benefit.
 *
 * <p>pgvector accepts and returns vectors in textual form like {@code [0.1,0.2,...]};
 * we (de)serialize against that representation rather than registering custom JDBC
 * types because HikariCP's per-connection initialization paths are clumsy.
 */
@Repository
public class JdbcBaselineRepository implements BaselineRepository {

    private static final String FIND_SQL =
            "SELECT entity_id, domain, embedding, event_count, last_updated "
          + "FROM baselines WHERE domain = ? AND entity_id = ?";

    private static final String UPSERT_SQL =
            "INSERT INTO baselines (entity_id, domain, embedding, event_count, last_updated) "
          + "VALUES (?, ?, ?::vector, ?, ?) "
          + "ON CONFLICT (entity_id, domain) DO UPDATE SET "
          + "  embedding = EXCLUDED.embedding, "
          + "  event_count = EXCLUDED.event_count, "
          + "  last_updated = EXCLUDED.last_updated";

    // pgvector's <=> is cosine DISTANCE, so cosine similarity = 1 - distance. The
    // comparison happens in-database (genuine vector-similarity search) rather than
    // pulling the 384-float vector back over JDBC to compare in Java.
    private static final String SCORE_LOOKUP_SQL =
            "SELECT event_count, (1 - (embedding <=> ?::vector)) AS cosine "
          + "FROM baselines WHERE domain = ? AND entity_id = ?";

    private static final RowMapper<Baseline> ROW_MAPPER = JdbcBaselineRepository::mapRow;

    private final JdbcTemplate jdbc;

    public JdbcBaselineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Baseline> find(String domain, String entityId) {
        try {
            Baseline baseline = jdbc.queryForObject(FIND_SQL, ROW_MAPPER, domain, entityId);
            return Optional.ofNullable(baseline);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void save(Baseline baseline) {
        jdbc.update(UPSERT_SQL,
                baseline.entityId(),
                baseline.domain(),
                formatVector(baseline.embedding()),
                baseline.eventCount(),
                Timestamp.from(baseline.lastUpdated()));
    }

    @Override
    public Optional<ScoreLookup> scoreLookup(String domain, String entityId, float[] vector) {
        try {
            ScoreLookup result = jdbc.queryForObject(
                    SCORE_LOOKUP_SQL,
                    (rs, rowNum) -> new ScoreLookup(rs.getDouble("cosine"), rs.getLong("event_count")),
                    formatVector(vector), domain, entityId);
            return Optional.ofNullable(result);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM baselines", Long.class);
        return n == null ? 0L : n;
    }

    private static Baseline mapRow(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Baseline(
                rs.getString("entity_id"),
                rs.getString("domain"),
                parseVector(rs.getString("embedding")),
                rs.getLong("event_count"),
                rs.getTimestamp("last_updated").toInstant());
    }

    /**
     * Format a float array as pgvector's text literal: {@code [0.1,0.2,...]}. We cast at
     * the SQL level via {@code ?::vector} so the driver doesn't try to be clever.
     */
    static String formatVector(float[] arr) {
        StringBuilder sb = new StringBuilder(arr.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /** Inverse of {@link #formatVector}. Tolerates whitespace inside the brackets. */
    static float[] parseVector(String text) {
        String inner = text.trim();
        if (inner.startsWith("[")) inner = inner.substring(1);
        if (inner.endsWith("]"))   inner = inner.substring(0, inner.length() - 1);
        if (inner.isBlank())       return new float[0];
        String[] parts = inner.split(",");
        float[] arr = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            arr[i] = Float.parseFloat(parts[i].trim());
        }
        return arr;
    }
}
