package io.conclave.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DecisionOrchestratorPropertiesTest {

    @Test
    void valid_record_constructs() {
        DecisionOrchestratorProperties p = new DecisionOrchestratorProperties(
                "localhost:9093", 1500L, "conclave-orchestrator", 3);
        assertThat(p.deliberationTarget()).isEqualTo("localhost:9093");
        assertThat(p.deliberationDeadlineMs()).isEqualTo(1500L);
    }

    @Test
    void blank_target_rejected() {
        assertThatThrownBy(() -> new DecisionOrchestratorProperties("", 1L, "g", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deliberation-target");
    }

    @Test
    void null_target_rejected() {
        assertThatThrownBy(() -> new DecisionOrchestratorProperties(null, 1L, "g", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zero_deadline_rejected() {
        assertThatThrownBy(() -> new DecisionOrchestratorProperties("t", 0L, "g", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deliberation-deadline-ms");
    }

    @Test
    void negative_deadline_rejected() {
        assertThatThrownBy(() -> new DecisionOrchestratorProperties("t", -10L, "g", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blank_group_id_rejected() {
        assertThatThrownBy(() -> new DecisionOrchestratorProperties("t", 1L, "", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kafka-consumer-group-id");
    }

    @Test
    void zero_partitions_rejected() {
        assertThatThrownBy(() -> new DecisionOrchestratorProperties("t", 1L, "g", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decisions-topic-partitions");
    }
}
