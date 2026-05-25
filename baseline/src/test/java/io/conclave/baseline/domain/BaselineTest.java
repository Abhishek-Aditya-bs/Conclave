package io.conclave.baseline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Branch-coverage tests for the {@link Baseline} record's manual equals / hashCode /
 * toString. Records' default implementations don't work correctly with {@code float[]}
 * fields, so we override them — and need tests to prove the overrides behave correctly.
 */
class BaselineTest {

    private final Instant t = Instant.parse("2026-05-25T10:00:00Z");

    @Test
    @DisplayName("equals is reflexive and identity-aware")
    void equalsReflexiveAndShortCircuits() {
        Baseline a = new Baseline("e1", "fraud", new float[]{1, 2}, 1, t);
        assertThat(a).isEqualTo(a);                  // same reference
        assertThat(a).isNotEqualTo(null);            // null check
        assertThat(a).isNotEqualTo("not-a-baseline"); // wrong type
    }

    @Test
    @DisplayName("equals is true for distinct instances with the same field values")
    void equalsByValue() {
        Baseline a = new Baseline("e1", "fraud", new float[]{1, 2, 3}, 5, t);
        Baseline b = new Baseline("e1", "fraud", new float[]{1, 2, 3}, 5, t);
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    @DisplayName("equals discriminates on every field, including float[] content")
    void notEqualWhenAnyFieldDiffers() {
        Baseline base = new Baseline("e1", "fraud", new float[]{1, 2, 3}, 5, t);
        assertThat(base).isNotEqualTo(new Baseline("e2", "fraud", new float[]{1, 2, 3}, 5, t));
        assertThat(base).isNotEqualTo(new Baseline("e1", "security", new float[]{1, 2, 3}, 5, t));
        assertThat(base).isNotEqualTo(new Baseline("e1", "fraud", new float[]{1, 2, 4}, 5, t));
        assertThat(base).isNotEqualTo(new Baseline("e1", "fraud", new float[]{1, 2, 3}, 6, t));
        assertThat(base).isNotEqualTo(new Baseline("e1", "fraud", new float[]{1, 2, 3}, 5, t.plusMillis(1)));
    }

    @Test
    @DisplayName("toString mentions embedding length but not the actual floats")
    void toStringIsCompact() {
        Baseline b = new Baseline("e1", "fraud", new float[384], 1, t);
        String s = b.toString();
        assertThat(s)
                .contains("e1")
                .contains("fraud")
                .contains("384 floats")
                .contains("eventCount=1");
    }

    @Test
    @DisplayName("dimensions() reports the embedding length")
    void dimensionsAccessor() {
        assertThat(new Baseline("e", "fraud", new float[]{1, 2, 3, 4}, 1, t).dimensions())
                .isEqualTo(4);
    }

    @Test
    @DisplayName("Canonical constructor rejects nulls and non-positive event counts")
    void canonicalConstructorRejectsBadInputs() {
        assertThatThrownBy(() -> new Baseline(null, "fraud", new float[1], 1, t))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Baseline("e", null, new float[1], 1, t))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Baseline("e", "fraud", null, 1, t))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Baseline("e", "fraud", new float[1], 1, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Baseline("e", "fraud", new float[1], 0, t))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventCount");
    }
}
