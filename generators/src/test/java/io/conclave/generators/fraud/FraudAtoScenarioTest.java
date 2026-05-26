package io.conclave.generators.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.events.fraud.PaymentEvent;
import io.conclave.generators.LabeledEvent;
import io.conclave.generators.Labels;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FraudAtoScenarioTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("emits warmup labeled CLEAN, then takeover labeled FRAUD_ATO")
    void warmupThenTakeover() {
        FraudAtoScenario scenario = new FraudAtoScenario(0, 3, 2, new Random(42), BASE);
        List<LabeledEvent> events = scenario.generate().toList();
        assertThat(events).hasSize(5);
        assertThat(events.subList(0, 3)).allMatch(e -> e.label() == Labels.CLEAN);
        assertThat(events.subList(3, 5)).allMatch(e -> e.label() == Labels.FRAUD_ATO);
    }

    @Test
    @DisplayName("takeover events flip device and IP relative to warmup")
    void regimeShiftVisible() {
        FraudAtoScenario scenario = new FraudAtoScenario(1, 2, 2, new Random(42), BASE);
        List<LabeledEvent> events = scenario.generate().toList();
        PaymentEvent warmup = (PaymentEvent) events.get(0).event();
        PaymentEvent takeover = (PaymentEvent) events.get(2).event();
        assertThat(warmup.getDeviceFingerprint()).isNotEqualTo(takeover.getDeviceFingerprint());
        assertThat(warmup.getIpAddress()).isNotEqualTo(takeover.getIpAddress());
        // cardholder stays the same — same account, hijacked
        assertThat(warmup.getCardholderId()).isEqualTo(takeover.getCardholderId());
    }

    @Test
    @DisplayName("takeover amounts are dramatically higher than warmup")
    void amountsEscalate() {
        FraudAtoScenario scenario = new FraudAtoScenario(0, 1, 1, new Random(7), BASE);
        List<LabeledEvent> events = scenario.generate().toList();
        long warmup = ((PaymentEvent) events.get(0).event()).getAmountMinor();
        long takeover = ((PaymentEvent) events.get(1).event()).getAmountMinor();
        assertThat(takeover).isGreaterThan(warmup * 5);
    }
}
