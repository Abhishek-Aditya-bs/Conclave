package io.conclave.orchestrator.domain;

/**
 * One per-decision driver, mirroring the {@code ContributingFactor} proto
 * message M5 emits.
 *
 * <p>{@code weight} is signed in {@code [-1, 1]}: positive nudges toward
 * BLOCK, negative toward ALLOW, zero is informational only. {@code evidence}
 * is the one-sentence excerpt the audit UI can show without parsing the full
 * Markdown explanation.
 */
public record ContributingFactorRecord(
        String name,
        double weight,
        String evidence
) {
    public ContributingFactorRecord {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (weight < -1.0 || weight > 1.0) {
            throw new IllegalArgumentException(
                    "weight must be in [-1, 1]; got " + weight);
        }
        if (evidence == null) {
            throw new IllegalArgumentException("evidence must not be null");
        }
    }
}
