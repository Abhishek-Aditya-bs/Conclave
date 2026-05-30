package io.conclave.graph.template.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the card-testing-ring risk curve (no Neo4j needed). Verifies the
 * count-driven ramp: nothing below 3 cardholders, suspicious (0.60) at the threshold,
 * and a confirmed ring (>= 0.80) once 5+ distinct cardholders share one device.
 */
class FraudCardTestingRingRiskTest {

    @Test
    void noRingBelowThreshold() {
        assertThat(FraudCardTestingRingTemplate.riskFor(0)).isEqualTo(0.0);
        assertThat(FraudCardTestingRingTemplate.riskFor(2)).isEqualTo(0.0);
    }

    @Test
    void suspiciousAtThreshold() {
        assertThat(FraudCardTestingRingTemplate.riskFor(3)).isCloseTo(0.60, within(1e-9));
    }

    @Test
    void confirmedRingReachesBlockGrade() {
        // 4 distinct cardholders on one device is a clear ring → >= 0.80 (BLOCK-grade).
        assertThat(FraudCardTestingRingTemplate.riskFor(4)).isCloseTo(0.80, within(1e-9));
        assertThat(FraudCardTestingRingTemplate.riskFor(5)).isGreaterThanOrEqualTo(0.80);
    }

    @Test
    void capsAtOne() {
        assertThat(FraudCardTestingRingTemplate.riskFor(5)).isEqualTo(1.0);
        assertThat(FraudCardTestingRingTemplate.riskFor(50)).isEqualTo(1.0);
    }
}
