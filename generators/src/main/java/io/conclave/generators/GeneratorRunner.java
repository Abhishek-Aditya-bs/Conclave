package io.conclave.generators;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drains a list of {@link Scenario}s into the given {@link EventPublisher}. Stops on
 * the first publish exception so a transient broker problem aborts the run instead
 * of producing a half-truthful labeled stream.
 *
 * <p>Returned counts let the CLI surface a one-line "5000 events / 4750 CLEAN / 250
 * CARD_TESTING_RING" summary. Useful for the demo voiceover.
 */
public class GeneratorRunner {

    private static final Logger LOG = LoggerFactory.getLogger(GeneratorRunner.class);

    private final EventPublisher publisher;

    public GeneratorRunner(EventPublisher publisher) {
        this.publisher = publisher;
    }

    public RunSummary run(List<Scenario> scenarios) {
        LongAdder total = new LongAdder();
        LongAdder clean = new LongAdder();
        LongAdder adversarial = new LongAdder();
        for (Scenario scenario : scenarios) {
            scenario.generate().forEach(event -> {
                publisher.publish(event);
                total.increment();
                if (event.label() == Labels.CLEAN) {
                    clean.increment();
                } else {
                    adversarial.increment();
                }
            });
        }
        RunSummary summary = new RunSummary(total.sum(), clean.sum(), adversarial.sum());
        LOG.info("generator run complete: total={}, clean={}, adversarial={}",
                summary.total(), summary.clean(), summary.adversarial());
        return summary;
    }

    public record RunSummary(long total, long clean, long adversarial) {}
}
