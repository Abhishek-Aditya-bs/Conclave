package io.conclave.generators.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.events.security.AuthEvent;
import io.conclave.events.security.AuthMethod;
import io.conclave.generators.LabeledEvent;
import io.conclave.generators.Labels;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LateralMovementScenarioTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("one principal touches distinct hosts; first hop is PASSWORD then SSH_KEY")
    void onePrincipalManyHosts() {
        LateralMovementScenario scenario = new LateralMovementScenario(3, 8, new Random(1), BASE);
        List<LabeledEvent> events = scenario.generate().toList();
        assertThat(events).hasSize(8);
        assertThat(events).allMatch(e -> e.label() == Labels.LATERAL_MOVEMENT);

        Set<String> principals = events.stream()
                .map(e -> ((AuthEvent) e.event()).getPrincipalId())
                .collect(Collectors.toSet());
        assertThat(principals).containsExactly("user_lateral_3");

        Set<String> hosts = events.stream()
                .map(e -> ((AuthEvent) e.event()).getHostId())
                .collect(Collectors.toSet());
        assertThat(hosts).hasSize(8);

        assertThat(((AuthEvent) events.get(0).event()).getAuthMethod()).isEqualTo(AuthMethod.PASSWORD);
        for (int i = 1; i < events.size(); i++) {
            assertThat(((AuthEvent) events.get(i).event()).getAuthMethod()).isEqualTo(AuthMethod.SSH_KEY);
        }
    }

    @Test
    @DisplayName("hops share one session id (post-foothold pivot)")
    void sharedSession() {
        LateralMovementScenario scenario = new LateralMovementScenario(0, 5, new Random(2), BASE);
        Set<String> sessions = scenario.generate()
                .map(e -> ((AuthEvent) e.event()).getSessionId())
                .collect(Collectors.toSet());
        assertThat(sessions).hasSize(1);
    }
}
