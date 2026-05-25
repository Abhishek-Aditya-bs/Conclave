package io.conclave.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DecisionRecordTest {

    private static DecisionRecord newValid() {
        return new DecisionRecord(
                UUID.randomUUID(),
                "evt-1",
                "fraud",
                0.83,
                "BLOCK",
                "Block — ring detected.",
                List.of(new ContributingFactorRecord("graph_ring_detected", 0.8, "ev")),
                240L,
                "anthropic",
                "claude-haiku-4-5-20251001",
                "{\"eventId\":\"evt-1\"}",
                Instant.parse("2026-05-26T00:00:00Z")
        );
    }

    @Test
    void valid_decision_constructs() {
        DecisionRecord d = newValid();
        assertThat(d.eventId()).isEqualTo("evt-1");
        assertThat(d.judgeModel()).isEqualTo("claude-haiku-4-5-20251001");
    }

    @Test
    void contributing_factors_are_defensively_copied() {
        var original = new ArrayList<ContributingFactorRecord>();
        original.add(new ContributingFactorRecord("a", 0.1, "x"));
        DecisionRecord d = new DecisionRecord(
                UUID.randomUUID(), "evt-1", "fraud", 0.5, "REVIEW", "Review.",
                original, 1L, "anthropic", "m", "{}", Instant.now());

        // Mutate the input list AFTER construction.
        original.clear();

        // The record's view is unchanged.
        assertThat(d.contributingFactors()).hasSize(1);
        // And the inner list is unmodifiable.
        assertThatThrownBy(() -> d.contributingFactors().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void blank_event_id_rejected() {
        assertThatThrownBy(() -> new DecisionRecord(
                UUID.randomUUID(), "", "fraud", 0.5, "REVIEW", "x",
                List.of(), 1L, "p", "m", "{}", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void score_out_of_range_rejected() {
        assertThatThrownBy(() -> new DecisionRecord(
                UUID.randomUUID(), "e", "fraud", 1.5, "REVIEW", "x",
                List.of(), 1L, "p", "m", "{}", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[0, 1]");
    }

    @Test
    void negative_latency_rejected() {
        assertThatThrownBy(() -> new DecisionRecord(
                UUID.randomUUID(), "e", "fraud", 0.5, "REVIEW", "x",
                List.of(), -1L, "p", "m", "{}", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latencyMs");
    }

    @Test
    void null_decision_id_rejected() {
        assertThatThrownBy(() -> new DecisionRecord(
                null, "e", "fraud", 0.5, "REVIEW", "x",
                List.of(), 1L, "p", "m", "{}", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null_factors_rejected() {
        assertThatThrownBy(() -> new DecisionRecord(
                UUID.randomUUID(), "e", "fraud", 0.5, "REVIEW", "x",
                null, 1L, "p", "m", "{}", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blank_domain_rejected() {
        assertThatThrownBy(() -> new DecisionRecord(
                UUID.randomUUID(), "e", "  ", 0.5, "REVIEW", "x",
                List.of(), 1L, "p", "m", "{}", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blank_verdict_label_rejected() {
        assertThatThrownBy(() -> new DecisionRecord(
                UUID.randomUUID(), "e", "fraud", 0.5, "", "x",
                List.of(), 1L, "p", "m", "{}", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
