package io.conclave.generators.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.events.fraud.PaymentEvent;
import io.conclave.generators.LabeledEvent;
import io.conclave.generators.Labels;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CardTestingRingScenarioTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("each ring uses one device and one IP across N cards × M attempts")
    void ringHasSingleDeviceManyCards() {
        CardTestingRingScenario scenario = new CardTestingRingScenario(2, 6, 4, new Random(42), BASE);
        List<LabeledEvent> events = scenario.generate().toList();
        assertThat(events).hasSize(24); // 6 × 4
        assertThat(events).allMatch(e -> e.label() == Labels.CARD_TESTING_RING);
        Set<String> devices = events.stream()
                .map(e -> ((PaymentEvent) e.event()).getDeviceFingerprint())
                .collect(Collectors.toSet());
        assertThat(devices).containsExactly("dev_ring_2");
        Set<String> ips = events.stream()
                .map(e -> ((PaymentEvent) e.event()).getIpAddress())
                .collect(Collectors.toSet());
        assertThat(ips).hasSize(1);
        Set<String> cardholders = events.stream()
                .map(e -> ((PaymentEvent) e.event()).getCardholderId())
                .collect(Collectors.toSet());
        assertThat(cardholders).hasSize(6); // distinct cardholders, all from this ring
    }

    @Test
    @DisplayName("ring amounts are all small (< 500 minor units)")
    void amountsAreSmall() {
        CardTestingRingScenario scenario = new CardTestingRingScenario(0, 3, 2, new Random(1), BASE);
        scenario.generate().forEach(e -> {
            long amount = ((PaymentEvent) e.event()).getAmountMinor();
            assertThat(amount).isBetween(100L, 499L);
        });
    }

    @Test
    @DisplayName("scenarioId stable per ring")
    void scenarioIdStable() {
        CardTestingRingScenario scenario = new CardTestingRingScenario(7, 2, 2, new Random(0), BASE);
        Set<String> ids = scenario.generate().map(LabeledEvent::scenarioId).collect(Collectors.toSet());
        assertThat(ids).containsExactly("card-testing-ring-7");
    }
}
