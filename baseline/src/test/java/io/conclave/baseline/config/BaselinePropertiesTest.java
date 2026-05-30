package io.conclave.baseline.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Branch-coverage tests for the {@link BaselineProperties} validation rules.
 */
class BaselinePropertiesTest {

    @Test
    @DisplayName("Valid construction with in-range decay and positive dim succeeds")
    void validProperties() {
        BaselineProperties props = new BaselineProperties(0.5, 384, 0.5, 0.5);
        assertThat(props.emaDecay()).isEqualTo(0.5);
        assertThat(props.embeddingDim()).isEqualTo(384);
    }

    @Test
    @DisplayName("ema-decay below 0 is rejected")
    void rejectsNegativeDecay() {
        assertThatThrownBy(() -> new BaselineProperties(-0.01, 384, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ema-decay");
    }

    @Test
    @DisplayName("ema-decay = 1.0 is rejected (would mean new events are completely ignored)")
    void rejectsDecayOne() {
        assertThatThrownBy(() -> new BaselineProperties(1.0, 384, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ema-decay");
    }

    @Test
    @DisplayName("ema-decay > 1.0 is rejected")
    void rejectsDecayAboveOne() {
        assertThatThrownBy(() -> new BaselineProperties(1.5, 384, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("embedding-dim of zero is rejected")
    void rejectsZeroDimension() {
        assertThatThrownBy(() -> new BaselineProperties(0.5, 0, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding-dim");
    }

    @Test
    @DisplayName("embedding-dim < 0 is rejected")
    void rejectsNegativeDimension() {
        assertThatThrownBy(() -> new BaselineProperties(0.5, -1, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
