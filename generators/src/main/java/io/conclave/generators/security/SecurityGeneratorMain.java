package io.conclave.generators.security;

import io.conclave.generators.CliOptions;
import io.conclave.generators.EventPublisher;
import io.conclave.generators.GeneratorDomain;
import io.conclave.generators.GeneratorRunner;
import io.conclave.generators.Scenario;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI entry point for the security synthetic stream generator.
 *
 * <p>Example: {@code java -cp generators-*.jar
 * io.conclave.generators.security.SecurityGeneratorMain --clean 3000 --rings 2 --ato 1 --extra 2}.
 * Here {@code --rings} = number of lateral-movement campaigns and
 * {@code --extra} = number of exfiltration campaigns.
 */
public final class SecurityGeneratorMain {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityGeneratorMain.class);

    /** Defaults sized for a 30-second demo loop. */
    public static final CliOptions.Defaults DEFAULTS = new CliOptions.Defaults(2000, 2, 1, 1);

    private SecurityGeneratorMain() {}

    public static void main(String[] args) {
        CliOptions opts;
        try {
            opts = CliOptions.parse(args, DEFAULTS);
        } catch (CliOptions.HelpRequested ex) {
            System.exit(0);
            return;
        } catch (IllegalArgumentException ex) {
            System.err.println("error: " + ex.getMessage());
            System.exit(2);
            return;
        }

        Random random = new Random(opts.seed());
        Instant baseTime = Instant.now();
        List<Scenario> scenarios = planScenarios(opts, random, baseTime);

        LOG.info("starting security generator: bootstrap={}, schema_registry={}, seed={}",
                opts.bootstrapServers(), opts.schemaRegistryUrl(), opts.seed());
        try (EventPublisher publisher = new EventPublisher(
                GeneratorDomain.SECURITY, opts.bootstrapServers(), opts.schemaRegistryUrl())) {
            GeneratorRunner.RunSummary summary = new GeneratorRunner(publisher).run(scenarios);
            LOG.info("done: {} events ({} clean, {} adversarial)",
                    summary.total(), summary.clean(), summary.adversarial());
        }
    }

    public static List<Scenario> planScenarios(CliOptions opts, Random random, Instant baseTime) {
        List<Scenario> scenarios = new ArrayList<>();
        if (opts.cleanCount() > 0) {
            scenarios.add(new CleanAuthScenario(opts.cleanCount(), random, baseTime));
        }
        for (int i = 0; i < opts.rings(); i++) {
            scenarios.add(new LateralMovementScenario(i, 8, random, baseTime));
        }
        for (int i = 0; i < opts.atoCampaigns(); i++) {
            scenarios.add(new SecurityAtoScenario(i, 5, 4, random, baseTime));
        }
        for (int i = 0; i < opts.extraCampaigns(); i++) {
            scenarios.add(new ExfiltrationScenario(i, 6, random, baseTime));
        }
        return scenarios;
    }
}
