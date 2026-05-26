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

class BustOutScenarioTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("ramp is monotonically non-decreasing; bust events are large")
    void rampThenBust() {
        BustOutScenario scenario = new BustOutScenario(0, 5, 3, new Random(7), BASE);
        List<LabeledEvent> events = scenario.generate().toList();
        assertThat(events).hasSize(8);
        assertThat(events.subList(0, 5)).allMatch(e -> e.label() == Labels.CLEAN);
        assertThat(events.subList(5, 8)).allMatch(e -> e.label() == Labels.BUST_OUT);

        long rampMax = events.subList(0, 5).stream()
                .mapToLong(e -> ((PaymentEvent) e.event()).getAmountMinor())
                .max().orElseThrow();
        long bustMin = events.subList(5, 8).stream()
                .mapToLong(e -> ((PaymentEvent) e.event()).getAmountMinor())
                .min().orElseThrow();
        assertThat(bustMin).isGreaterThan(rampMax * 10);
    }

    @Test
    @DisplayName("same cardholder across ramp + bust (one account being torched)")
    void singleCardholder() {
        BustOutScenario scenario = new BustOutScenario(3, 4, 2, new Random(1), BASE);
        List<LabeledEvent> events = scenario.generate().toList();
        long distinct = events.stream()
                .map(e -> ((PaymentEvent) e.event()).getCardholderId())
                .distinct()
                .count();
        assertThat(distinct).isEqualTo(1L);
    }
}
