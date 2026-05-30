package io.conclave.stream;

import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import io.conclave.events.security.AuthEvent;
import io.conclave.events.security.AuthResult;
import io.conclave.events.security.EnrichedAuthEvent;
import io.conclave.ingest.EventDomain;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.processor.api.FixedKeyProcessor;
import org.apache.kafka.streams.processor.api.FixedKeyProcessorContext;
import org.apache.kafka.streams.processor.api.FixedKeyRecord;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Feature-extraction spec for the SOC configuration.
 *
 * <p>Computed features:
 * <ul>
 *   <li>{@code principalVelocity} — cumulative count of AuthEvents seen for this
 *       principalId.</li>
 *   <li>{@code failedLoginsRecent} — cumulative count of non-SUCCESS results for this
 *       principalId.</li>
 *   <li>{@code baselineEntityId} — the principalId, which the baseline service keys on.</li>
 *   <li>{@code graphEntityIds} — entities the graph reasoner considers: principal, host, source IP,
 *       and target resource if present.</li>
 * </ul>
 *
 * <p>Two state stores rather than a tuple-valued store keeps the serde wiring simple
 * (both are {@code KeyValueStore<String, Long>} with Avro irrelevance).
 */
@Component
@Profile("security")
public class SecurityFeatureSpec implements FeatureSpec<AuthEvent, EnrichedAuthEvent> {

    static final String TOTAL_STORE  = "security-principal-total-velocity";
    static final String FAILED_STORE = "security-principal-failed-velocity";

    private final Serde<AuthEvent> rawSerde;
    private final Serde<EnrichedAuthEvent> enrichedSerde;

    public SecurityFeatureSpec(
            @Value("${spring.kafka.properties.schema.registry.url}") String schemaRegistryUrl) {
        Map<String, Object> serdeConfig = Map.of(
                "schema.registry.url", schemaRegistryUrl,
                "specific.avro.reader", "true",
                "auto.register.schemas", "true");
        SpecificAvroSerde<AuthEvent> rs = new SpecificAvroSerde<>();
        rs.configure(serdeConfig, false);
        SpecificAvroSerde<EnrichedAuthEvent> es = new SpecificAvroSerde<>();
        es.configure(serdeConfig, false);
        this.rawSerde = rs;
        this.enrichedSerde = es;
    }

    @Override public EventDomain domain()                       { return EventDomain.SECURITY; }
    @Override public Class<AuthEvent> rawType()                 { return AuthEvent.class; }
    @Override public Class<EnrichedAuthEvent> enrichedType()    { return EnrichedAuthEvent.class; }
    @Override public Serde<AuthEvent> rawSerde()                { return rawSerde; }
    @Override public Serde<EnrichedAuthEvent> enrichedSerde()   { return enrichedSerde; }

    @Override
    public void configureStores(StreamsBuilder builder) {
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(TOTAL_STORE),
                Serdes.String(), Serdes.Long()));
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(FAILED_STORE),
                Serdes.String(), Serdes.Long()));
    }

    @Override
    public KStream<String, EnrichedAuthEvent> enrich(KStream<String, AuthEvent> raw) {
        return raw
                .selectKey((k, v) -> v.getPrincipalId())
                .processValues(
                        () -> new EnrichmentProcessor(TOTAL_STORE, FAILED_STORE, this::buildEnriched),
                        TOTAL_STORE, FAILED_STORE)
                .selectKey((k, v) -> v.getEventId());
    }

    /** Visible for unit tests. */
    EnrichedAuthEvent buildEnriched(AuthEvent raw, long total, long failed) {
        List<String> graphIds = new ArrayList<>(4);
        graphIds.add(raw.getPrincipalId());
        graphIds.add(raw.getHostId());
        graphIds.add(raw.getSourceIp());
        if (raw.getTargetResource() != null) {
            graphIds.add(raw.getTargetResource());
        }
        return EnrichedAuthEvent.newBuilder()
                .setEventId(raw.getEventId())
                .setTimestamp(raw.getTimestamp())
                .setPrincipalId(raw.getPrincipalId())
                .setHostId(raw.getHostId())
                .setSourceIp(raw.getSourceIp())
                .setAuthMethod(raw.getAuthMethod().name())
                .setResult(raw.getResult().name())
                .setTargetResource(raw.getTargetResource())
                .setUserAgent(raw.getUserAgent())
                .setSessionId(raw.getSessionId())
                .setIsPrivileged(raw.getIsPrivileged())
                .setPrincipalVelocity(total)
                .setFailedLoginsRecent(failed)
                .setBaselineEntityId(raw.getPrincipalId())
                .setGraphEntityIds(graphIds)
                .setFeatureExtractedAt(Instant.now())
                .build();
    }

    @FunctionalInterface
    interface EnrichedBuilder {
        EnrichedAuthEvent build(AuthEvent raw, long total, long failed);
    }

    static final class EnrichmentProcessor
            implements FixedKeyProcessor<String, AuthEvent, EnrichedAuthEvent> {

        private final String totalStoreName;
        private final String failedStoreName;
        private final EnrichedBuilder builder;
        private KeyValueStore<String, Long> totalStore;
        private KeyValueStore<String, Long> failedStore;
        private FixedKeyProcessorContext<String, EnrichedAuthEvent> context;

        EnrichmentProcessor(String totalStoreName, String failedStoreName, EnrichedBuilder builder) {
            this.totalStoreName = totalStoreName;
            this.failedStoreName = failedStoreName;
            this.builder = builder;
        }

        @Override
        public void init(FixedKeyProcessorContext<String, EnrichedAuthEvent> context) {
            this.context = context;
            this.totalStore = context.getStateStore(totalStoreName);
            this.failedStore = context.getStateStore(failedStoreName);
        }

        @Override
        public void process(FixedKeyRecord<String, AuthEvent> record) {
            String key = record.key();
            long total = inc(totalStore, key);
            long failed = record.value().getResult() == AuthResult.SUCCESS
                    ? Long.valueOf(failedStore.get(key) == null ? 0L : failedStore.get(key))
                    : inc(failedStore, key);
            context.forward(record.withValue(builder.build(record.value(), total, failed)));
        }

        private static long inc(KeyValueStore<String, Long> store, String key) {
            Long prev = store.get(key);
            long next = (prev == null ? 0L : prev) + 1L;
            store.put(key, next);
            return next;
        }
    }
}
