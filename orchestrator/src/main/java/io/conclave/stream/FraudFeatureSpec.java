package io.conclave.stream;

import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import io.conclave.events.fraud.EnrichedPaymentEvent;
import io.conclave.events.fraud.PaymentEvent;
import io.conclave.ingest.EventDomain;
import java.time.Instant;
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
 * Feature-extraction spec for the fraud configuration.
 *
 * <p>Computed features:
 * <ul>
 *   <li>{@code cardholderVelocity} — cumulative count of PaymentEvents seen for this
 *       cardholder, including the current event. Uses a persistent key-value store keyed
 *       by cardholderId so counts survive restarts.</li>
 *   <li>{@code binRiskScore} — deterministic synthetic risk score in {@code [0, 1)}
 *       derived from the BIN. Placeholder; real BIN intelligence belongs in a side-input
 *       topic the topology can KTable-join against (left for a future task).</li>
 *   <li>{@code baselineEntityId} — the cardholderId, which the baseline service uses to fetch the
 *       behavioral baseline embedding.</li>
 *   <li>{@code graphEntityIds} — the entity identifiers the graph reasoner considers (cardholder, device,
 *       IP, merchant).</li>
 * </ul>
 */
@Component
@Profile("fraud")
public class FraudFeatureSpec implements FeatureSpec<PaymentEvent, EnrichedPaymentEvent> {

    /** Persistent KV store: cardholderId → cumulative event count. */
    static final String VELOCITY_STORE = "fraud-cardholder-velocity";

    private final Serde<PaymentEvent> rawSerde;
    private final Serde<EnrichedPaymentEvent> enrichedSerde;

    public FraudFeatureSpec(
            @Value("${spring.kafka.properties.schema.registry.url}") String schemaRegistryUrl) {
        Map<String, Object> serdeConfig = Map.of(
                "schema.registry.url", schemaRegistryUrl,
                "specific.avro.reader", "true",
                "auto.register.schemas", "true");
        SpecificAvroSerde<PaymentEvent> rs = new SpecificAvroSerde<>();
        rs.configure(serdeConfig, /* isKey */ false);
        SpecificAvroSerde<EnrichedPaymentEvent> es = new SpecificAvroSerde<>();
        es.configure(serdeConfig, false);
        this.rawSerde = rs;
        this.enrichedSerde = es;
    }

    @Override public EventDomain domain()                            { return EventDomain.FRAUD; }
    @Override public Class<PaymentEvent> rawType()                   { return PaymentEvent.class; }
    @Override public Class<EnrichedPaymentEvent> enrichedType()      { return EnrichedPaymentEvent.class; }
    @Override public Serde<PaymentEvent> rawSerde()                  { return rawSerde; }
    @Override public Serde<EnrichedPaymentEvent> enrichedSerde()     { return enrichedSerde; }

    @Override
    public void configureStores(StreamsBuilder builder) {
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(VELOCITY_STORE),
                Serdes.String(),
                Serdes.Long()));
    }

    @Override
    public KStream<String, EnrichedPaymentEvent> enrich(KStream<String, PaymentEvent> raw) {
        return raw
                .selectKey((k, v) -> v.getCardholderId())
                .processValues(() -> new EnrichmentProcessor(VELOCITY_STORE, this::buildEnriched),
                               VELOCITY_STORE)
                .selectKey((k, v) -> v.getEventId());
    }

    /** Visible for unit tests. */
    EnrichedPaymentEvent buildEnriched(PaymentEvent raw, long velocity) {
        return EnrichedPaymentEvent.newBuilder()
                .setEventId(raw.getEventId())
                .setTimestamp(raw.getTimestamp())
                .setCardholderId(raw.getCardholderId())
                .setCardToken(raw.getCardToken())
                .setAmountMinor(raw.getAmountMinor())
                .setCurrency(raw.getCurrency())
                .setMerchantId(raw.getMerchantId())
                .setMerchantCategoryCode(raw.getMerchantCategoryCode())
                .setBin(raw.getBin())
                .setDeviceFingerprint(raw.getDeviceFingerprint())
                .setIpAddress(raw.getIpAddress())
                .setBillingCountry(raw.getBillingCountry())
                .setShippingCountry(raw.getShippingCountry())
                .setCardPresent(raw.getCardPresent())
                .setChannel(raw.getChannel().name())
                .setCardholderVelocity(velocity)
                .setBinRiskScore(computeBinRiskScore(raw.getBin()))
                .setBaselineEntityId(raw.getCardholderId())
                .setGraphEntityIds(List.of(
                        raw.getCardholderId(),
                        raw.getDeviceFingerprint(),
                        raw.getIpAddress(),
                        raw.getMerchantId()))
                .setFeatureExtractedAt(Instant.now())
                .build();
    }

    static double computeBinRiskScore(String bin) {
        int hash = bin.hashCode() & 0x7fffffff;
        return (hash % 1000) / 1000.0;
    }

    /**
     * Stateful processor: increment per-cardholder counter, emit enriched event.
     * Package-private + static so unit tests can exercise it directly if needed.
     */
    static final class EnrichmentProcessor
            implements FixedKeyProcessor<String, PaymentEvent, EnrichedPaymentEvent> {

        private final String storeName;
        private final java.util.function.BiFunction<PaymentEvent, Long, EnrichedPaymentEvent> builder;
        private KeyValueStore<String, Long> store;
        private FixedKeyProcessorContext<String, EnrichedPaymentEvent> context;

        EnrichmentProcessor(String storeName,
                            java.util.function.BiFunction<PaymentEvent, Long, EnrichedPaymentEvent> builder) {
            this.storeName = storeName;
            this.builder = builder;
        }

        @Override
        public void init(FixedKeyProcessorContext<String, EnrichedPaymentEvent> context) {
            this.context = context;
            this.store = context.getStateStore(storeName);
        }

        @Override
        public void process(FixedKeyRecord<String, PaymentEvent> record) {
            Long prev = store.get(record.key());
            long count = (prev == null ? 0 : prev) + 1;
            store.put(record.key(), count);
            context.forward(record.withValue(builder.apply(record.value(), count)));
        }
    }
}
