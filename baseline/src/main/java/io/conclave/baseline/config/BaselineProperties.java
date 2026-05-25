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
 */
@ConfigurationProperties(prefix = "conclave.baseline")
public record BaselineProperties(
        @DefaultValue("0.85") double emaDecay,
        @DefaultValue("384")   int    embeddingDim) {

    public BaselineProperties {
        if (emaDecay < 0.0 || emaDecay >= 1.0) {
            throw new IllegalArgumentException(
                    "conclave.baseline.ema-decay must be in [0, 1), got " + emaDecay);
        }
        if (embeddingDim <= 0) {
            throw new IllegalArgumentException(
                    "conclave.baseline.embedding-dim must be > 0, got " + embeddingDim);
        }
    }
}
