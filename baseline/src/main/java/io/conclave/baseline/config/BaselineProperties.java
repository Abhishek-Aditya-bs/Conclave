package io.conclave.baseline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration bound from {@code conclave.baseline.*}.
 *
 * @param emaDecay      weight on the existing baseline in the rolling update. New event
 *                      weight = {@code 1 - emaDecay}. Range {@code [0, 1)}.
 * @param embeddingDim  dimension of vectors stored in the {@code embedding} column.
 *                      Must match the {@code EmbeddingService} implementation in use
 *                      (langchain4j MiniLM-L6-v2 = 384). Fails fast at startup if mismatched.
 * @param coldStartScore anomaly score ScoreEvent returns for an entity with no baseline
 *                      yet (no history to compare against). A neutral prior, not a
 *                      measured deviation. Range {@code [0, 1]}.
 * @param scoreMaxDistance cosine DISTANCE (= {@code 1 - cosine_similarity}) at which the
 *                      ScoreEvent anomaly score saturates to 1.0. Range {@code (0, 2]};
 *                      lower = more sensitive. Tune per domain during benchmarking.
 */
@ConfigurationProperties(prefix = "conclave.baseline")
public record BaselineProperties(
        @DefaultValue("0.85") double emaDecay,
        @DefaultValue("384")   int    embeddingDim,
        @DefaultValue("0.5")   double coldStartScore,
        @DefaultValue("0.5")   double scoreMaxDistance) {

    public BaselineProperties {
        if (emaDecay < 0.0 || emaDecay >= 1.0) {
            throw new IllegalArgumentException(
                    "conclave.baseline.ema-decay must be in [0, 1), got " + emaDecay);
        }
        if (embeddingDim <= 0) {
            throw new IllegalArgumentException(
                    "conclave.baseline.embedding-dim must be > 0, got " + embeddingDim);
        }
        if (coldStartScore < 0.0 || coldStartScore > 1.0) {
            throw new IllegalArgumentException(
                    "conclave.baseline.cold-start-score must be in [0, 1], got " + coldStartScore);
        }
        // Cosine distance ranges over [0, 2]; the saturation point must be positive.
        if (scoreMaxDistance <= 0.0 || scoreMaxDistance > 2.0) {
            throw new IllegalArgumentException(
                    "conclave.baseline.score-max-distance must be in (0, 2], got " + scoreMaxDistance);
        }
    }

    /**
     * Convenience constructor for callers (mainly tests) that predate the ScoreEvent
     * scoring parameters; applies the same defaults as the {@code @DefaultValue}
     * annotations. Spring's {@code @ConfigurationProperties} binding always uses a
     * record's canonical constructor, so this extra constructor does not affect binding.
     */
    public BaselineProperties(double emaDecay, int embeddingDim) {
        this(emaDecay, embeddingDim, 0.5, 0.5);
    }
}
