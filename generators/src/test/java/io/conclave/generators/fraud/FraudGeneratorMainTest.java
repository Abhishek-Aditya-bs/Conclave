package io.conclave.generators.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.generators.CliOptions;
import io.conclave.generators.Scenario;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FraudGeneratorMainTest {

    @Test
    @DisplayName("default options plan one clean + N rings + ATO + bust-out")
    void plansAllScenarioTypes() {
        CliOptions opts = CliOptions.parse(new String[]{
                "--clean", "10", "--rings", "2", "--ato", "1", "--extra", "1"
        }, FraudGeneratorMain.DEFAULTS);
        List<Scenario> scenarios = FraudGeneratorMain.planScenarios(
                opts, new Random(0), Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(scenarios).hasSize(5); // 1 clean + 2 rings + 1 ATO + 1 bust-out
        assertThat(scenarios.get(0)).isInstanceOf(CleanFraudScenario.class);
        assertThat(scenarios.get(1)).isInstanceOf(CardTestingRingScenario.class);
        assertThat(scenarios.get(2)).isInstanceOf(CardTestingRingScenario.class);
        assertThat(scenarios.get(3)).isInstanceOf(FraudAtoScenario.class);
        assertThat(scenarios.get(4)).isInstanceOf(BustOutScenario.class);
    }

    @Test
    @DisplayName("--clean 0 omits the clean scenario")
    void zeroCleanOmits() {
        CliOptions opts = CliOptions.parse(new String[]{
                "--clean", "0", "--rings", "1", "--ato", "0", "--extra", "0"
        }, FraudGeneratorMain.DEFAULTS);
        List<Scenario> scenarios = FraudGeneratorMain.planScenarios(
                opts, new Random(0), Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(scenarios).hasSize(1);
        assertThat(scenarios.get(0)).isInstanceOf(CardTestingRingScenario.class);
    }
}
