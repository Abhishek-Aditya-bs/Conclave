package io.conclave.orchestrator.storage;

import io.conclave.orchestrator.domain.DecisionRecord;

/**
 * Minimal write-side interface — the M7 audit API will add the read-side
 * methods. Splitting writer from reader now keeps the orchestrator's
 * dependency footprint tight and makes the storage implementation
 * substitutable for tests.
 */
public interface DecisionRepository {

    /**
     * Persist a decision row. Throws on any failure (e.g. DB down,
     * constraint violation). M6's orchestrator catches such failures and
     * routes the event to the DLQ rather than swallowing them.
     */
    void save(DecisionRecord decision);
}
