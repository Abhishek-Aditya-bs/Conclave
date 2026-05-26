package io.conclave.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DecisionFilterTest {

    @Test
    void empty_filter_has_safe_defaults() {
        DecisionFilter f = DecisionFilter.empty();
        assertThat(f.limit()).isEqualTo(DecisionFilter.DEFAULT_LIMIT);
        assertThat(f.offset()).isZero();
        assertThat(f.domain()).isEmpty();
        assertThat(f.minScore()).isEmpty();
        assertThat(f.maxScore()).isEmpty();
    }

    @Test
    void builder_populates_fields() {
        DecisionFilter f = DecisionFilter.builder()
                .domain("fraud")
                .verdictLabel("BLOCK")
                .baselineEntityId("cardholder-9")
                .minScore(0.3)
                .maxScore(0.9)
                .since(Instant.parse("2026-05-25T00:00:00Z"))
                .until(Instant.parse("2026-05-27T00:00:00Z"))
                .judgeProvider("anthropic")
                .limit(25)
                .offset(50)
                .build();

        assertThat(f.domain()).contains("fraud");
        assertThat(f.minScore()).contains(0.3);
        assertThat(f.maxScore()).contains(0.9);
        assertThat(f.limit()).isEqualTo(25);
        assertThat(f.offset()).isEqualTo(50);
    }

    @Test
    void blank_string_fields_collapse_to_empty() {
        DecisionFilter f = DecisionFilter.builder()
                .domain("  ").verdictLabel(null).baselineEntityId("")
                .build();
        assertThat(f.domain()).isEmpty();
        assertThat(f.verdictLabel()).isEmpty();
        assertThat(f.baselineEntityId()).isEmpty();
    }

    @Test
    void zero_limit_rejected() {
        assertThatThrownBy(() -> DecisionFilter.builder().limit(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void limit_above_max_rejected() {
        assertThatThrownBy(() ->
                DecisionFilter.builder().limit(DecisionFilter.MAX_LIMIT + 1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("<= " + DecisionFilter.MAX_LIMIT);
    }

    @Test
    void negative_offset_rejected() {
        assertThatThrownBy(() -> DecisionFilter.builder().offset(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset");
    }

    @Test
    void score_out_of_range_rejected() {
        assertThatThrownBy(() -> DecisionFilter.builder().minScore(-0.1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minScore");
        assertThatThrownBy(() -> DecisionFilter.builder().maxScore(1.1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxScore");
    }

    @Test
    void min_score_greater_than_max_rejected() {
        assertThatThrownBy(() ->
                DecisionFilter.builder().minScore(0.7).maxScore(0.3).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("<= maxScore");
    }
}
