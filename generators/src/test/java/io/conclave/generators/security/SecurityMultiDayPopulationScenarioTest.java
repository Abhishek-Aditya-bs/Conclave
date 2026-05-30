package io.conclave.generators.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.events.security.AuthEvent;
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

/** Unit tests for the multi-day, same-principal security population generator. */
class SecurityMultiDayPopulationScenarioTest {

    private static final Instant WINDOW = Instant.parse("2026-05-01T00:00:00Z");

    @Test
    @DisplayName("emits principals × days × eventsPerDay clean auth events")
    void emitsExpectedCount() {
        SecurityMultiDayPopulationScenario scenario = new SecurityMultiDayPopulationScenario(
                12, 5, 2, new Random(7), WINDOW);
        List<LabeledEvent> events = scenario.generate().toList();
        assertThat(events).hasSize(12 * 5 * 2);
        assertThat(events).allMatch(e -> e.label() == Labels.CLEAN);
    }

    @Test
    @DisplayName("each principal recurs across the whole window (same-principal history)")
    void samePrincipalAcrossDays() {
        int principals = 9;
        int days = 6;
        int perDay = 3;
        SecurityMultiDayPopulationScenario scenario = new SecurityMultiDayPopulationScenario(
                principals, days, perDay, new Random(11), WINDOW);
        List<LabeledEvent> events = scenario.generate().toList();

        Map<String, Long> byPrincipal = events.stream()
                .map(e -> ((AuthEvent) e.event()).getPrincipalId().toString())
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()));

        assertThat(byPrincipal).hasSize(principals);
        assertThat(byPrincipal.values()).allMatch(n -> n == (long) days * perDay);
        assertThat(byPrincipal.keySet()).allMatch(id -> id.startsWith("user_pop_"));
    }

    @Test
    @DisplayName("event ids are unique and timestamps fall inside the window")
    void uniqueIdsWithinWindow() {
        int days = 8;
        SecurityMultiDayPopulationScenario scenario = new SecurityMultiDayPopulationScenario(
                10, days, 2, new Random(3), WINDOW);
        List<LabeledEvent> events = scenario.generate().toList();

        Set<String> ids = events.stream()
                .map(e -> ((AuthEvent) e.event()).getEventId().toString())
                .collect(Collectors.toSet());
        assertThat(ids).hasSize(events.size());

        Instant end = WINDOW.plusSeconds((long) days * 24 * 3600);
        assertThat(events).allMatch(e -> {
            Instant ts = (Instant) ((AuthEvent) e.event()).getTimestamp();
            return !ts.isBefore(WINDOW) && ts.isBefore(end);
        });
    }

    @Test
    @DisplayName("zero principals produces no events")
    void zeroPrincipalsIsEmpty() {
        SecurityMultiDayPopulationScenario scenario = new SecurityMultiDayPopulationScenario(
                0, 10, 5, new Random(1), WINDOW);
        assertThat(scenario.generate().toList()).isEmpty();
    }
}
