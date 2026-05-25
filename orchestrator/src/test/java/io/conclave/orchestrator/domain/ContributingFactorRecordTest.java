package io.conclave.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ContributingFactorRecordTest {

    @Test
    void valid_factor_is_constructable() {
        var factor = new ContributingFactorRecord("graph_ring_detected", 0.8, "Device touched 7 cardholders");
        assertThat(factor.name()).isEqualTo("graph_ring_detected");
        assertThat(factor.weight()).isEqualTo(0.8);
        assertThat(factor.evidence()).isEqualTo("Device touched 7 cardholders");
    }

    @Test
    void blank_name_rejected() {
        assertThatThrownBy(() -> new ContributingFactorRecord("", 0.0, "ev"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name must not be blank");
    }

    @Test
    void null_name_rejected() {
        assertThatThrownBy(() -> new ContributingFactorRecord(null, 0.0, "ev"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void weight_out_of_range_rejected() {
        assertThatThrownBy(() -> new ContributingFactorRecord("n", 1.5, "ev"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[-1, 1]");
        assertThatThrownBy(() -> new ContributingFactorRecord("n", -1.5, "ev"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundary_weights_accepted() {
        assertThat(new ContributingFactorRecord("n", -1.0, "ev").weight()).isEqualTo(-1.0);
        assertThat(new ContributingFactorRecord("n", 1.0, "ev").weight()).isEqualTo(1.0);
        assertThat(new ContributingFactorRecord("n", 0.0, "ev").weight()).isZero();
    }

    @Test
    void null_evidence_rejected() {
        assertThatThrownBy(() -> new ContributingFactorRecord("n", 0.0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
