package io.conclave.generators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CliOptionsTest {

    private static final CliOptions.Defaults DEFAULTS = new CliOptions.Defaults(100, 2, 1, 1);

    @Test
    @DisplayName("no args → defaults preserved")
    void defaultsPreserved() {
        CliOptions opts = CliOptions.parse(new String[]{}, DEFAULTS);
        assertThat(opts.cleanCount()).isEqualTo(100);
        assertThat(opts.rings()).isEqualTo(2);
        assertThat(opts.atoCampaigns()).isEqualTo(1);
        assertThat(opts.extraCampaigns()).isEqualTo(1);
        assertThat(opts.seed()).isEqualTo(42L);
        assertThat(opts.bootstrapServers()).isEqualTo("localhost:9092");
        assertThat(opts.schemaRegistryUrl()).isEqualTo("mock://conclave-default");
    }

    @Test
    @DisplayName("each flag overrides its default")
    void allFlagsHonored() {
        CliOptions opts = CliOptions.parse(new String[]{
                "--bootstrap", "k:1",
                "--schema-registry", "mock://x",
                "--seed", "7",
                "--clean", "10",
                "--rings", "3",
                "--ato", "4",
                "--extra", "5"
        }, DEFAULTS);
        assertThat(opts.bootstrapServers()).isEqualTo("k:1");
        assertThat(opts.schemaRegistryUrl()).isEqualTo("mock://x");
        assertThat(opts.seed()).isEqualTo(7L);
        assertThat(opts.cleanCount()).isEqualTo(10);
        assertThat(opts.rings()).isEqualTo(3);
        assertThat(opts.atoCampaigns()).isEqualTo(4);
        assertThat(opts.extraCampaigns()).isEqualTo(5);
    }

    @Test
    @DisplayName("unknown flag fails fast")
    void unknownFlag() {
        assertThatThrownBy(() -> CliOptions.parse(new String[]{"--bogus", "1"}, DEFAULTS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--bogus");
    }

    @Test
    @DisplayName("flag without value fails fast")
    void flagMissingValue() {
        assertThatThrownBy(() -> CliOptions.parse(new String[]{"--seed"}, DEFAULTS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a value");
    }

    @Test
    @DisplayName("negative count is rejected")
    void negativeCountRejected() {
        assertThatThrownBy(() -> CliOptions.parse(new String[]{"--clean", "-1"}, DEFAULTS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 0");
    }

    @Test
    @DisplayName("--help raises HelpRequested without parsing further")
    void helpShortCircuits() {
        assertThatThrownBy(() -> CliOptions.parse(new String[]{"--help"}, DEFAULTS))
                .isInstanceOf(CliOptions.HelpRequested.class);
    }
}
