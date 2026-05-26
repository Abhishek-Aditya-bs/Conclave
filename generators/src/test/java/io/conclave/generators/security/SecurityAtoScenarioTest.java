package io.conclave.generators.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.events.security.AuthEvent;
import io.conclave.events.security.AuthMethod;
import io.conclave.generators.LabeledEvent;
import io.conclave.generators.Labels;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecurityAtoScenarioTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("warmup is CLEAN/SSO from home IP, then takeover is SECURITY_ATO with foreign IP")
    void regimeShift() {
        SecurityAtoScenario scenario = new SecurityAtoScenario(2, 4, 3, new Random(8), BASE);
        List<LabeledEvent> events = scenario.generate().toList();
        assertThat(events).hasSize(7);
        assertThat(events.subList(0, 4)).allMatch(e -> e.label() == Labels.CLEAN);
        assertThat(events.subList(0, 4))
                .allMatch(e -> ((AuthEvent) e.event()).getAuthMethod() == AuthMethod.SSO);
        assertThat(events.subList(4, 7)).allMatch(e -> e.label() == Labels.SECURITY_ATO);
        assertThat(events.subList(4, 7))
                .allMatch(e -> ((AuthEvent) e.event()).getAuthMethod() == AuthMethod.PASSWORD);

        AuthEvent warmup = (AuthEvent) events.get(0).event();
        AuthEvent takeover = (AuthEvent) events.get(4).event();
        assertThat(warmup.getPrincipalId()).isEqualTo(takeover.getPrincipalId());
        assertThat(warmup.getSourceIp()).isNotEqualTo(takeover.getSourceIp());
    }
}
