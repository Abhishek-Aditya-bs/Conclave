package io.conclave.graph.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GraphFindingTest {

    @Test
    @DisplayName("Construction with valid arguments succeeds")
    void validConstruction() {
        GraphFinding f = new GraphFinding("t", "e1", "fraud",
                Map.of("k", "v"), 0.5, 12L);
        assertThat(f.templateName()).isEqualTo("t");
        assertThat(f.rootEntityId()).isEqualTo("e1");
        assertThat(f.domain()).isEqualTo("fraud");
        assertThat(f.riskSignal()).isEqualTo(0.5);
        assertThat(f.queryLatencyMs()).isEqualTo(12L);
    }

    @Test
    @DisplayName("Null fields are rejected")
    void rejectsNullFields() {
        assertThatThrownBy(() -> new GraphFinding(null, "e", "f", Map.of(), 0.0, 0L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraphFinding("t", null, "f", Map.of(), 0.0, 0L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraphFinding("t", "e", null, Map.of(), 0.0, 0L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraphFinding("t", "e", "f", null, 0.0, 0L))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("riskSignal must be in [0, 1]")
    void rejectsOutOfRangeRiskSignal() {
        assertThatThrownBy(() -> new GraphFinding("t", "e", "f", Map.of(), -0.01, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riskSignal");
        assertThatThrownBy(() -> new GraphFinding("t", "e", "f", Map.of(), 1.01, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        // Boundaries are allowed.
        new GraphFinding("t", "e", "f", Map.of(), 0.0, 0L);
        new GraphFinding("t", "e", "f", Map.of(), 1.0, 0L);
    }

    @Test
    @DisplayName("queryLatencyMs must be >= 0")
    void rejectsNegativeLatency() {
        assertThatThrownBy(() -> new GraphFinding("t", "e", "f", Map.of(), 0.0, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queryLatencyMs");
    }

    @Test
    @DisplayName("withLatency returns a copy stamped with the new latency")
    void withLatencyCopies() {
        GraphFinding original = new GraphFinding("t", "e", "fraud",
                Map.of("k", "v"), 0.5, 0L);
        GraphFinding stamped = original.withLatency(42L);
        assertThat(stamped.queryLatencyMs()).isEqualTo(42L);
        // All other fields preserved.
        assertThat(stamped.templateName()).isEqualTo(original.templateName());
        assertThat(stamped.rootEntityId()).isEqualTo(original.rootEntityId());
        assertThat(stamped.domain()).isEqualTo(original.domain());
        assertThat(stamped.attributes()).isEqualTo(original.attributes());
        assertThat(stamped.riskSignal()).isEqualTo(original.riskSignal());
        // Original unchanged.
        assertThat(original.queryLatencyMs()).isEqualTo(0L);
    }
}
