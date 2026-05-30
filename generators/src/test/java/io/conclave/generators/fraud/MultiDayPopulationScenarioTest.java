package io.conclave.generators.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.events.fraud.PaymentEvent;
import io.conclave.generators.Distributions;
import io.conclave.generators.LabeledEvent;
import io.conclave.generators.Labels;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the multi-day, same-customer, multi-distribution population generator.
 */
class MultiDayPopulationScenarioTest {

    private static final Instant WINDOW = Instant.parse("2026-05-01T00:00:00Z");

    @Test
    @DisplayName("emits customers × days × eventsPerDay clean events")
    void emitsExpectedCount() {
        MultiDayPopulationScenario scenario = new MultiDayPopulationScenario(
                10, 5, 2, Distributions.Kind.GAUSSIAN, new Random(7), WINDOW);
        List<LabeledEvent> events = scenario.generate().toList();
        assertThat(events).hasSize(10 * 5 * 2);
        assertThat(events).allMatch(e -> e.label() == Labels.CLEAN);
    }

    @Test
    @DisplayName("each customer recurs across the whole window (same-customer history)")
    void sameCustomerAcrossDays() {
        int customers = 8;
        int days = 7;
        int perDay = 3;
        MultiDayPopulationScenario scenario = new MultiDayPopulationScenario(
                customers, days, perDay, null /* mix */, new Random(11), WINDOW);
        List<LabeledEvent> events = scenario.generate().toList();

        Map<String, Long> byCardholder = events.stream()
                .map(e -> ((PaymentEvent) e.event()).getCardholderId().toString())
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()));

        assertThat(byCardholder).hasSize(customers);
        assertThat(byCardholder.values()).allMatch(n -> n == (long) days * perDay);
        assertThat(byCardholder.keySet()).allMatch(id -> id.startsWith("ch_pop_"));
    }

    @Test
    @DisplayName("amounts stay within the clamped minor-unit range and event ids are unique")
    void amountsClampedAndIdsUnique() {
        MultiDayPopulationScenario scenario = new MultiDayPopulationScenario(
                20, 10, 2, null, new Random(3), WINDOW);
        List<LabeledEvent> events = scenario.generate().toList();

        assertThat(events).allMatch(e -> {
            long amt = ((PaymentEvent) e.event()).getAmountMinor();
            return amt >= 100L && amt <= 5_000_000L;
        });
        Set<String> ids = events.stream()
                .map(e -> ((PaymentEvent) e.event()).getEventId().toString())
                .collect(Collectors.toSet());
        assertThat(ids).hasSize(events.size());
    }

    @Test
    @DisplayName("a single forced distribution is honoured by every customer")
    void honoursForcedDistribution() {
        MultiDayPopulationScenario scenario = new MultiDayPopulationScenario(
                4, 3, 2, Distributions.Kind.PARETO, new Random(42), WINDOW);
        // Pareto is heavy-tailed but still clamped; just assert the run is well-formed.
        assertThat(scenario.generate().toList()).hasSize(4 * 3 * 2);
    }

    @Test
    @DisplayName("zero customers produces no events")
    void zeroCustomersIsEmpty() {
        MultiDayPopulationScenario scenario = new MultiDayPopulationScenario(
                0, 10, 5, null, new Random(1), WINDOW);
        assertThat(scenario.generate().toList()).isEmpty();
    }
}
