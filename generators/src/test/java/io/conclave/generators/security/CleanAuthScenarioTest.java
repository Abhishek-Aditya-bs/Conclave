package io.conclave.generators.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.events.security.AuthEvent;
import io.conclave.generators.LabeledEvent;
import io.conclave.generators.Labels;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CleanAuthScenarioTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("emits requested count, all CLEAN, all non-privileged with null targetResource")
    void countAndShape() {
        CleanAuthScenario scenario = new CleanAuthScenario(80, new Random(11), BASE);
        List<LabeledEvent> events = scenario.generate().toList();
        assertThat(events).hasSize(80);
        assertThat(events).allMatch(e -> e.label() == Labels.CLEAN);
        events.forEach(e -> {
            AuthEvent ae = (AuthEvent) e.event();
            assertThat(ae.getEventId()).isNotBlank();
            assertThat(ae.getIsPrivileged()).isFalse();
            assertThat(ae.getTargetResource()).isNull();
        });
    }

    @Test
    @DisplayName("clean traffic spans many distinct principals")
    void diversePrincipals() {
        CleanAuthScenario scenario = new CleanAuthScenario(400, new Random(13), BASE);
        Set<String> principals = scenario.generate()
                .map(e -> ((AuthEvent) e.event()).getPrincipalId())
                .collect(Collectors.toSet());
        assertThat(principals.size()).isGreaterThan(100);
    }
}
