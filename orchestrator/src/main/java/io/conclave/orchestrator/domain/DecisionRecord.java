package io.conclave.orchestrator.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Java-side mirror of the judge's {@code Decision} proto message, plus the
 * orchestrator-only metadata we persist alongside it.
 *
 * <p>Two layers of stamping happen between the judge and the decisions table:
 * <ol>
 *   <li>The judge returns score / verdict / explanation / factors / latency /
 *       judge_provider / judge_model — those map 1:1 from the proto.</li>
 *   <li>The orchestrator stamps {@link #decisionId} (a fresh UUID), {@link #createdAt},
 *       and keeps the {@link #enrichedEventJson} payload it sent so the audit
 *       UI / replay endpoint can reconstruct the evidence without
 *       walking back to Kafka.</li>
 * </ol>
 *
 * <p>{@code verdictLabel} is a string here rather than an enum because the judge
 * is the source of truth for the verdict vocabulary; if the judge grows a new
 * label (e.g. "REVIEW_AUTO"), we should be permissive on the consumer side
 * and validate at the audit boundary instead.
 */
public record DecisionRecord(
        UUID decisionId,
        String eventId,
        String domain,
        // Identifier the baseline service was keyed on (cardholderId for
        // fraud, principalId for security). Persisted as a first-class column
        // so the audit API can filter decisions by entity without parsing
        // {@code enrichedEventJson} on every query.
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
    public DecisionRecord {
        if (decisionId == null) {
            throw new IllegalArgumentException("decisionId must not be null");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain must not be blank");
        }
        if (baselineEntityId == null || baselineEntityId.isBlank()) {
            throw new IllegalArgumentException("baselineEntityId must not be blank");
        }
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be in [0, 1]; got " + score);
        }
        if (verdictLabel == null || verdictLabel.isBlank()) {
            throw new IllegalArgumentException("verdictLabel must not be blank");
        }
        if (verdictExplanationMd == null) {
            throw new IllegalArgumentException("verdictExplanationMd must not be null");
        }
        if (contributingFactors == null) {
            throw new IllegalArgumentException("contributingFactors must not be null");
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must be >= 0; got " + latencyMs);
        }
        if (judgeProvider == null) {
            throw new IllegalArgumentException("judgeProvider must not be null");
        }
        if (judgeModel == null) {
            throw new IllegalArgumentException("judgeModel must not be null");
        }
        if (enrichedEventJson == null) {
            throw new IllegalArgumentException("enrichedEventJson must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
        // Defensive copy so the record is genuinely immutable.
        contributingFactors = List.copyOf(contributingFactors);
    }
}
