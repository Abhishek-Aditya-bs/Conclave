package io.conclave.stream;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;

/**
 * Shared topology shell for the M2 feature-extraction job. Given a {@link FeatureSpec}, this
 * builds a {@code Topology} that:
 *
 * <ol>
 *   <li>declares any state stores the spec needs,
 *   <li>reads {@code (String, R)} pairs from the spec's input topic,
 *   <li>hands the resulting {@code KStream} to {@link FeatureSpec#enrich},
 *   <li>writes the resulting {@code (String, E)} pairs to the output topic.
 * </ol>
 *
 * <p>That's it — every per-domain decision lives in the spec. Kept as a static utility
 * because there's no state worth holding here and Spring won't need to inject it.
 */
public final class FeatureExtractionTopology {

    private FeatureExtractionTopology() {
        /* static-only */
    }

    public static <R extends SpecificRecord, E extends SpecificRecord> Topology build(
            FeatureSpec<R, E> spec) {
        StreamsBuilder builder = new StreamsBuilder();
        spec.configureStores(builder);

        KStream<String, R> raw = builder.stream(
                spec.inputTopic(),
                Consumed.with(Serdes.String(), spec.rawSerde()));

        KStream<String, E> enriched = spec.enrich(raw);

        enriched.to(
                spec.outputTopic(),
                Produced.with(Serdes.String(), spec.enrichedSerde()));

        return builder.build();
    }
}
