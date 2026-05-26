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

class ExfiltrationScenarioTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("each event hits a sensitive resource on the same sensitive host, privileged")
    void sensitiveTargets() {
        ExfiltrationScenario scenario = new ExfiltrationScenario(0, 5, new Random(3), BASE);
        List<LabeledEvent> events = scenario.generate().toList();
        assertThat(events).hasSize(5);
        assertThat(events).allMatch(e -> e.label() == Labels.EXFILTRATION);
        assertThat(events).allMatch(e -> ((AuthEvent) e.event()).getIsPrivileged());
        assertThat(events).allMatch(e -> ((AuthEvent) e.event()).getTargetResource() != null);

        Set<String> hosts = events.stream()
                .map(e -> ((AuthEvent) e.event()).getHostId())
                .collect(Collectors.toSet());
        assertThat(hosts).hasSize(1); // pinned to one sensitive host per campaign

        Set<String> resources = events.stream()
                .map(e -> ((AuthEvent) e.event()).getTargetResource())
                .collect(Collectors.toSet());
        assertThat(resources.size()).isGreaterThanOrEqualTo(2); // multiple sensitive reads
    }
}
