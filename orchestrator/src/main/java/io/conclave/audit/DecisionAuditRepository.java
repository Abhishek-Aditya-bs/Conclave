package io.conclave.audit;

import io.conclave.orchestrator.domain.DecisionRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side repository for the audit API.
 *
 * <p>Kept separate from {@code DecisionRepository} (the writer) so the
 * audit hot path doesn't accumulate write methods over time, and so the
 * REST controller has a minimal injectable surface.
 */
public interface DecisionAuditRepository {

    /** Fetch one decision by id. {@link Optional#empty()} on miss. */
    Optional<DecisionRecord> findById(UUID decisionId);

    /** Page of summaries matching {@code filter}, newest first. */
    List<DecisionSummary> findAll(DecisionFilter filter);

    /** Unfiltered match count for the same predicate set (excluding limit/offset). */
    long count(DecisionFilter filter);
}
