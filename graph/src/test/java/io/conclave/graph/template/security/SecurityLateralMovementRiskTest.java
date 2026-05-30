package io.conclave.graph.template.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the lateral-movement risk curve (no Neo4j needed). Verifies the
 * count-driven ramp: nothing below 5 hosts, suspicious (0.60) at the threshold, and
 * confirmed lateral movement (>= 0.80) once a principal hits 7+ distinct hosts.
 */
class SecurityLateralMovementRiskTest {

    @Test
    void noMovementBelowThreshold() {
        assertThat(SecurityLateralMovementTemplate.riskFor(0)).isEqualTo(0.0);
        assertThat(SecurityLateralMovementTemplate.riskFor(4)).isEqualTo(0.0);
    }

    @Test
    void suspiciousAtThreshold() {
        assertThat(SecurityLateralMovementTemplate.riskFor(5)).isCloseTo(0.60, within(1e-9));
    }

    @Test
    void confirmedMovementReachesBlockGrade() {
        // 6 distinct hosts for one principal is a clear lateral-movement campaign.
        assertThat(SecurityLateralMovementTemplate.riskFor(6)).isCloseTo(0.80, within(1e-9));
        assertThat(SecurityLateralMovementTemplate.riskFor(7)).isGreaterThanOrEqualTo(0.80);
    }

    @Test
    void capsAtOne() {
        assertThat(SecurityLateralMovementTemplate.riskFor(7)).isEqualTo(1.0);
        assertThat(SecurityLateralMovementTemplate.riskFor(40)).isEqualTo(1.0);
    }
}
