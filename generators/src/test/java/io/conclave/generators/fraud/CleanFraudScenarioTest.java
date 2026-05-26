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

class CleanFraudScenarioTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("emits requested count, all labeled CLEAN, fully populated events")
    void countAndShape() {
        CleanFraudScenario scenario = new CleanFraudScenario(100, new Random(42), BASE);
        List<LabeledEvent> events = scenario.generate().toList();
        assertThat(events).hasSize(100);
        assertThat(events).allMatch(e -> e.label() == Labels.CLEAN);
        events.forEach(e -> {
            PaymentEvent pe = (PaymentEvent) e.event();
            assertThat(pe.getEventId()).isNotBlank();
            assertThat(pe.getCardholderId()).startsWith("ch_");
            assertThat(pe.getAmountMinor()).isPositive();
            assertThat(pe.getCurrency()).isNotBlank();
        });
    }

    @Test
    @DisplayName("clean traffic uses many distinct cardholders (population diversity)")
    void diversePopulation() {
        CleanFraudScenario scenario = new CleanFraudScenario(500, new Random(123), BASE);
        Set<String> cardholders = scenario.generate()
                .map(e -> ((PaymentEvent) e.event()).getCardholderId())
                .collect(Collectors.toSet());
        assertThat(cardholders.size()).isGreaterThan(200); // diversity proxy
    }

    @Test
    @DisplayName("identical seed → identical event stream (determinism)")
    void deterministic() {
        List<LabeledEvent> first = new CleanFraudScenario(20, new Random(99), BASE).generate().toList();
        List<LabeledEvent> second = new CleanFraudScenario(20, new Random(99), BASE).generate().toList();
        assertThat(first).hasSize(second.size());
        for (int i = 0; i < first.size(); i++) {
            PaymentEvent a = (PaymentEvent) first.get(i).event();
            PaymentEvent b = (PaymentEvent) second.get(i).event();
            // eventId is a UUID (independent of seed), but every other field is RNG-derived
            assertThat(b.getCardholderId()).isEqualTo(a.getCardholderId());
            assertThat(b.getAmountMinor()).isEqualTo(a.getAmountMinor());
            assertThat(b.getBin()).isEqualTo(a.getBin());
        }
    }
}
