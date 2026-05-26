package io.conclave.generators;

import java.util.stream.Stream;

/**
 * A bounded stream of labeled events representing one pattern (clean traffic,
 * one card-testing ring, one ATO campaign, ...). The runner drains the stream
 * and publishes each {@link LabeledEvent} via {@link EventPublisher}.
 *
 * <p>Implementations should be deterministic given a fixed {@code Random} seed
 * so unit tests can assert on event counts and shapes.
 */
@FunctionalInterface
public interface Scenario {

    /** Emit the events that make up this scenario. */
    Stream<LabeledEvent> generate();
}
