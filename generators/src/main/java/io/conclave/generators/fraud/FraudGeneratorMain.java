package io.conclave.generators.fraud;

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
 * CLI entry point for the fraud synthetic stream generator. Boots a plain
 * Kafka producer, plans a mix of scenarios from the CLI args, and runs them.
 *
 * <p>Example: {@code java -cp generators-*.jar
 * io.conclave.generators.fraud.FraudGeneratorMain --clean 5000 --rings 3 --ato 2}.
 */
public final class FraudGeneratorMain {

    private static final Logger LOG = LoggerFactory.getLogger(FraudGeneratorMain.class);

    /** Defaults sized for a 30-second demo loop. */
    public static final CliOptions.Defaults DEFAULTS = new CliOptions.Defaults(2000, 2, 1, 1);

    private FraudGeneratorMain() {}

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

        LOG.info("starting fraud generator: bootstrap={}, schema_registry={}, seed={}",
                opts.bootstrapServers(), opts.schemaRegistryUrl(), opts.seed());
        try (EventPublisher publisher = new EventPublisher(
                GeneratorDomain.FRAUD, opts.bootstrapServers(), opts.schemaRegistryUrl())) {
            GeneratorRunner.RunSummary summary = new GeneratorRunner(publisher).run(scenarios);
            LOG.info("done: {} events ({} clean, {} adversarial)",
                    summary.total(), summary.clean(), summary.adversarial());
        }
    }

    /** Visible for tests so they can assert on planning logic without booting a producer. */
    public static List<Scenario> planScenarios(CliOptions opts, Random random, Instant baseTime) {
        List<Scenario> scenarios = new ArrayList<>();
        if (opts.cleanCount() > 0) {
            scenarios.add(new CleanFraudScenario(opts.cleanCount(), random, baseTime));
        }
        for (int i = 0; i < opts.rings(); i++) {
            scenarios.add(new CardTestingRingScenario(i, 8, 5, random, baseTime));
        }
        for (int i = 0; i < opts.atoCampaigns(); i++) {
            scenarios.add(new FraudAtoScenario(i, 6, 4, random, baseTime));
        }
        for (int i = 0; i < opts.extraCampaigns(); i++) {
            scenarios.add(new BustOutScenario(i, 8, 4, random, baseTime));
        }
        return scenarios;
    }
}
