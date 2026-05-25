package io.conclave.baseline.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pgvector text-format helpers in {@link JdbcBaselineRepository}.
 * Pure functions — no DB needed.
 */
class JdbcBaselineRepositoryTest {

    @Test
    @DisplayName("formatVector wraps a float array in pgvector text form")
    void formatProducesPgvectorLiteral() {
        assertThat(JdbcBaselineRepository.formatVector(new float[]{0.1f, 0.2f, 0.3f}))
                .isEqualTo("[0.1,0.2,0.3]");
    }

    @Test
    @DisplayName("formatVector handles an empty array")
    void formatEmpty() {
        assertThat(JdbcBaselineRepository.formatVector(new float[]{})).isEqualTo("[]");
    }

    @Test
    @DisplayName("parseVector recovers a float array from pgvector text form")
    void parseRecoversArray() {
        float[] arr = JdbcBaselineRepository.parseVector("[0.1,0.2,0.3]");
        assertThat(arr).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    @DisplayName("parseVector tolerates whitespace and an empty body")
    void parseTolerantOfWhitespace() {
        assertThat(JdbcBaselineRepository.parseVector("[ 0.5 , 0.5 ]"))
                .containsExactly(0.5f, 0.5f);
        assertThat(JdbcBaselineRepository.parseVector("[]")).isEmpty();
    }

    @Test
    @DisplayName("format → parse round-trip preserves values to float precision")
    void roundTripPreservesValues() {
        float[] original = new float[]{0.001f, -3.14f, 1e-7f, 42.0f};
        float[] recovered = JdbcBaselineRepository.parseVector(
                JdbcBaselineRepository.formatVector(original));
        assertThat(recovered).hasSameSizeAs(original);
        for (int i = 0; i < original.length; i++) {
            assertThat(recovered[i]).isCloseTo(original[i], within(1e-6f));
        }
    }
}
