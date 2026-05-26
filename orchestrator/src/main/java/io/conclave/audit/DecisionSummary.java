package io.conclave.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Light-weight view of a decision for the list endpoint.
 *
 * <p>Excludes {@code verdictExplanationMd}, {@code contributingFactors},
 * and {@code enrichedEventJson} — those are detail-only payloads. The
 * dashboard renders the list view as a table; rows show the columns
 * present here.
 *
 * <p>Serialized JSON shape (snake_case via Jackson default) mirrors the
 * record fields. M10 consumes this directly.
 */
public record DecisionSummary(
        UUID decisionId,
        String eventId,
        String domain,
        String baselineEntityId,
        double score,
        String verdictLabel,
        long latencyMs,
        String judgeProvider,
        String judgeModel,
        Instant createdAt
) {
}
