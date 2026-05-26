package io.conclave.audit;

import io.conclave.orchestrator.domain.ContributingFactorRecord;
import io.conclave.orchestrator.domain.DecisionRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full decision view, returned by {@code GET /api/v1/decisions/{id}} and
 * by the replay endpoint.
 *
 * <p>Mirrors {@link DecisionRecord} field-for-field. We keep a separate
 * DTO so the wire schema can evolve independently of the internal
 * domain type (e.g. if the audit API needs to add a {@code source}
 * field that doesn't belong on the persistence record).
 */
public record DecisionDetail(
        UUID decisionId,
        String eventId,
        String domain,
        String baselineEntityId,
        double score,
        String verdictLabel,
        String verdictExplanationMd,
        List<ContributingFactorRecord> contributingFactors,
        long latencyMs,
        String judgeProvider,
        String judgeModel,
        String enrichedEventJson,
        Instant createdAt
) {
    /** Project a {@link DecisionRecord} into the wire DTO. */
    public static DecisionDetail of(DecisionRecord record) {
        return new DecisionDetail(
                record.decisionId(),
                record.eventId(),
                record.domain(),
                record.baselineEntityId(),
                record.score(),
                record.verdictLabel(),
                record.verdictExplanationMd(),
                record.contributingFactors(),
                record.latencyMs(),
                record.judgeProvider(),
                record.judgeModel(),
                record.enrichedEventJson(),
                record.createdAt());
    }
}
