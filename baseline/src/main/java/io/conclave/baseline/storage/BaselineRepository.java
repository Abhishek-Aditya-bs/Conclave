package io.conclave.baseline.storage;

import io.conclave.baseline.domain.Baseline;
import java.util.Optional;

/**
 * Storage-layer contract for the baselines table. One implementation
 * ({@link JdbcBaselineRepository} backed by Postgres + pgvector); the interface lets
 * benchmarks substitute in-memory variants without rebuilding tests.
 */
public interface BaselineRepository {

    /**
     * Look up the current rolling baseline for an entity within a domain.
     * @return an empty {@link Optional} if no events have been observed.
     */
    Optional<Baseline> find(String domain, String entityId);

    /** Insert-or-update — the natural key is {@code (entity_id, domain)}. */
    void save(Baseline baseline);

    /**
     * Cosine similarity of {@code vector} to the stored baseline for
     * {@code (domain, entityId)}, computed in-database via pgvector's distance
     * operator, plus the baseline's event count. This is the vector-similarity
     * search backing {@code ScoreEvent}.
     *
     * @return empty if no baseline exists yet (cold-start entity).
     */
    Optional<ScoreLookup> scoreLookup(String domain, String entityId, float[] vector);

    /** Result of {@link #scoreLookup}: cosine similarity in {@code [-1, 1]} + event count. */
    record ScoreLookup(double cosineSimilarity, long eventCount) {}

    /** Test convenience: how many baselines are currently persisted. */
    long count();
}
