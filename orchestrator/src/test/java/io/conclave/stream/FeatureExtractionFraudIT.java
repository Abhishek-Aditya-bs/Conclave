package io.conclave.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.conclave.ConclaveApplication;
import io.conclave.events.fraud.EnrichedPaymentEvent;
import io.conclave.events.fraud.PaymentEvent;
import io.conclave.ingest.EventDomain;
import io.conclave.ingest.EventProducer;
import io.conclave.ingest.IngestProperties;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end integration test for feature extraction — fraud configuration.
 *
 * <p>Spring boot starts the producer SDK + the feature-extraction Kafka Streams app + a Testcontainers
 * Kafka broker. We publish a batch of PaymentEvents to {@code events.fraud.raw} via the
 * producer, then assert that the same count of EnrichedPaymentEvents appears on
 * {@code events.fraud.enriched} with the computed feature fields populated.
 */
@Testcontainers
@SpringBootTest(classes = ConclaveApplication.class,
        properties = {
                "spring.kafka.streams.state-dir=./target/test-streams-state-fraud",
                // This IT exercises only the feature-extraction topology;
                // dropping the orchestrator slice lets this run without Postgres / judge.
                "conclave.orchestrator.enabled=false"
        })
@ActiveProfiles("fraud")
class FeatureExtractionFraudIT {

    private static final String REGISTRY = "mock://m2-fraud-it";

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.properties.schema.registry.url", () -> REGISTRY);
    }

    @Autowired EventProducer producer;
    @Autowired IngestProperties properties;

    @Test
    @DisplayName("1000 raw PaymentEvents produce 1000 enriched events with computed features")
    void thousandEventsRoundTripThroughTopology() throws Exception {
        assertThat(properties.domain()).isEqualTo(EventDomain.FRAUD);
        final int total = 1000;

        // Publish: spread across 50 cardholders so velocity counters get exercised.
        Set<String> expectedIds = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < total; i++) {
            PaymentEvent e = TestEvents.paymentEventForCardholder("cust_" + (i % 50));
            expectedIds.add(e.getEventId());
            producer.publish(e).get(10, TimeUnit.SECONDS);
        }

        // Consume from the enriched topic.
        Map<String, EnrichedPaymentEvent> received = new HashMap<>();
        try (KafkaConsumer<String, EnrichedPaymentEvent> consumer = newConsumer()) {
            consumer.subscribe(List.of(EventDomain.FRAUD.enrichedTopic()));
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).until(() -> {
                ConsumerRecords<String, EnrichedPaymentEvent> batch = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, EnrichedPaymentEvent> r : batch) {
                    received.put(r.value().getEventId(), r.value());
                }
                return received.size() >= total;
            });
        }

        assertThat(received.keySet()).isEqualTo(expectedIds);
        // Spot-check the enriched payload: velocity > 0, baselineEntityId set, graph IDs present.
        for (EnrichedPaymentEvent e : received.values()) {
            assertThat(e.getCardholderVelocity()).isGreaterThan(0L);
            assertThat(e.getBaselineEntityId()).isNotBlank();
            assertThat(e.getGraphEntityIds()).hasSize(4);
            assertThat(e.getBinRiskScore()).isBetween(0.0, 1.0);
            assertThat(e.getFeatureExtractedAt()).isAfter(Instant.EPOCH);
        }
    }

    @Test
    @DisplayName("Burst of 2000 events arrives intact (backpressure smoke test)")
    void burstDoesNotLoseMessages() throws Exception {
        final int burst = 2000;
        Set<String> sent = new HashSet<>();
        long start = System.currentTimeMillis();
        for (int i = 0; i < burst; i++) {
            PaymentEvent e = TestEvents.paymentEventForCardholder("burst_" + (i % 20));
            sent.add(e.getEventId());
            producer.publish(e); // do NOT await each individually — fire and forget
        }
        long publishMs = System.currentTimeMillis() - start;

        Set<String> received = new HashSet<>();
        try (KafkaConsumer<String, EnrichedPaymentEvent> consumer = newConsumer()) {
            consumer.subscribe(List.of(EventDomain.FRAUD.enrichedTopic()));
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).until(() -> {
                ConsumerRecords<String, EnrichedPaymentEvent> batch = consumer.poll(Duration.ofMillis(500));
                batch.forEach(r -> received.add(r.value().getEventId()));
                return received.containsAll(sent);
            });
        }
        assertThat(received).containsAll(sent);
        // Sanity: publish should have been fast (events are async ack)
        assertThat(publishMs)
                .as("publishing 2000 fire-and-forget should complete in well under the consume budget")
                .isLessThan(60_000L);
    }

    private static KafkaConsumer<String, EnrichedPaymentEvent> newConsumer() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "fraud-it-consumer-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        config.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, REGISTRY);
        config.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        return new KafkaConsumer<>(config);
    }
}
