package io.conclave.generators;

import java.util.Objects;
import org.apache.avro.specific.SpecificRecord;

/**
 * Pair of an Avro event and its ground-truth label. Generator strategies return
 * a {@code Stream<LabeledEvent>} so the runner can publish both halves in lockstep.
 */
public record LabeledEvent(SpecificRecord event, Labels label, String scenarioId, String reason) {

    public LabeledEvent {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(scenarioId, "scenarioId");
        Objects.requireNonNull(reason, "reason");
    }
}
