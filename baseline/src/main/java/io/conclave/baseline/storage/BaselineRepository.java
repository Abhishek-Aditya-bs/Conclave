package io.conclave.baseline.storage;

import io.conclave.baseline.domain.Baseline;
import java.util.Optional;

/**
 * Storage-layer contract for the baselines table. One implementation in M3
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

    /** Test convenience: how many baselines are currently persisted. */
    long count();
}
