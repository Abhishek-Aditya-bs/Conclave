package io.conclave.ingest;

import java.util.concurrent.CompletableFuture;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.kafka.support.SendResult;

/**
 * Producer SDK for the M1 raw-event topics.
 *
 * <p>Implementations are expected to be thread-safe and to use the event's {@code eventId}
 * field as the Kafka record key, so downstream consumers can rely on per-event ordering
 * across retries.
 */
public interface EventProducer {

    /**
     * Publish an event to the active domain's raw topic.
     *
     * @param event a generated Avro {@code SpecificRecord} (e.g. {@code PaymentEvent} or
     *              {@code AuthEvent}). Must have a non-null {@code eventId} field.
     * @return a future that completes once the broker has acknowledged the write
     *         (or fails with the underlying Kafka exception).
     */
    CompletableFuture<SendResult<String, SpecificRecord>> publish(SpecificRecord event);
}
