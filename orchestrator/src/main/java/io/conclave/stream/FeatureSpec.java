package io.conclave.stream;

import io.conclave.ingest.EventDomain;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.KStream;

/**
 * Domain-agnostic contract for the feature-extraction stream job.
 *
 * <p>The reference architecture promises "one architecture, two domains" — both the fraud
 * and SOC configurations boot from the same binary and the only per-domain code lives in
 * implementations of this interface. The shared topology shell ({@link FeatureExtractionTopology})
 * reads from {@link #inputTopic()}, hands the resulting {@code KStream} to {@link #enrich},
 * and writes the result to {@link #outputTopic()}. Anything stateful (e.g. velocity
 * counters keyed by entity ID) lives in state stores declared via {@link #configureStores}.
 *
 * <p>Both the raw and enriched event types are Avro {@link SpecificRecord}s with their
 * schemas in {@code configs/{domain}/(enriched-)schema.avsc}.
 *
 * @param <R> raw event type — what producers emit (e.g. {@code PaymentEvent}).
 * @param <E> enriched event type — what the job emits (e.g. {@code EnrichedPaymentEvent}).
 */
public interface FeatureSpec<R extends SpecificRecord, E extends SpecificRecord> {

    /** Which reference configuration this spec serves. */
    EventDomain domain();

    /** Topic the topology reads from. Defaults to the domain's raw topic. */
    default String inputTopic() {
        return domain().rawTopic();
    }

    /** Topic the topology writes to. Defaults to the domain's enriched topic. */
    default String outputTopic() {
        return domain().enrichedTopic();
    }

    /** The raw Avro record class — used for serde construction and TopologyTestDriver. */
    Class<R> rawType();

    /** The enriched Avro record class. */
    Class<E> enrichedType();

    /**
     * Serde for the raw event. Implementations configure this once at startup (e.g. with
     * the Schema Registry URL) and return the same instance.
     */
    Serde<R> rawSerde();

    /** Serde for the enriched event. */
    Serde<E> enrichedSerde();

    /**
     * Declare any persistent or in-memory state stores the topology needs. Called by the
     * shared topology builder BEFORE {@link #enrich}. Default: no stores.
     */
    default void configureStores(StreamsBuilder builder) {
        /* no stores by default */
    }

    /**
     * Apply per-domain enrichment to the raw event stream. The returned stream MUST be
     * keyed by {@code eventId} (the shared topology shell writes it back to the enriched
     * topic; downstream consumers rely on the key being the event ID). Implementations
     * may re-key internally for aggregation as long as they re-key back before returning.
     */
    KStream<String, E> enrich(KStream<String, R> raw);
}
