package io.conclave.generators.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.generators.CliOptions;
import io.conclave.generators.Scenario;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecurityGeneratorMainTest {

    @Test
    @DisplayName("default plan: clean + lateral + ATO + exfil")
    void plansAllScenarioTypes() {
        CliOptions opts = CliOptions.parse(new String[]{
                "--clean", "10", "--rings", "1", "--ato", "1", "--extra", "2"
        }, SecurityGeneratorMain.DEFAULTS);
        List<Scenario> scenarios = SecurityGeneratorMain.planScenarios(
                opts, new Random(0), Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(scenarios).hasSize(5); // 1 clean + 1 lateral + 1 ATO + 2 exfil
        assertThat(scenarios.get(0)).isInstanceOf(CleanAuthScenario.class);
        assertThat(scenarios.get(1)).isInstanceOf(LateralMovementScenario.class);
        assertThat(scenarios.get(2)).isInstanceOf(SecurityAtoScenario.class);
        assertThat(scenarios.get(3)).isInstanceOf(ExfiltrationScenario.class);
        assertThat(scenarios.get(4)).isInstanceOf(ExfiltrationScenario.class);
    }
}
