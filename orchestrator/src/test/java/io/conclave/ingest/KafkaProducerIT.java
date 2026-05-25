package io.conclave.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.conclave.ConclaveApplication;
import io.conclave.events.fraud.PaymentEvent;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * End-to-end integration test for M1: publish 1000 PaymentEvents through the producer SDK,
 * consume them back off the broker, and assert that all 1000 event IDs arrive intact.
 *
 * <p>Uses Testcontainers Kafka in KRaft mode (multi-arch image, Apple Silicon-safe) and
 * Confluent's in-process {@code mock://} schema registry so we don't need a Schema Registry
 * container. The scope name is unique per test class so parallel test runs don't collide.
 */
@Testcontainers
@SpringBootTest(classes = ConclaveApplication.class)
@ActiveProfiles("fraud")
class KafkaProducerIT {

    private static final String REGISTRY_SCOPE = "conclave-it-fraud";
    private static final String REGISTRY_URL = "mock://" + REGISTRY_SCOPE;

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.producer.properties.schema.registry.url", () -> REGISTRY_URL);
    }

    @Autowired
    EventProducer producer;

    @Autowired
    IngestProperties properties;

    @Test
    @DisplayName("publishes 1000 PaymentEvents to events.fraud.raw; all are consumed back")
    void publishesAndConsumesOneThousandEvents() throws Exception {
        assertThat(properties.domain()).isEqualTo(EventDomain.FRAUD);

        // Publish.
        List<PaymentEvent> sent = EventFactory.paymentEvents(1000);
        Set<String> expectedIds = new HashSet<>();
        for (PaymentEvent event : sent) {
            expectedIds.add(event.getEventId());
            producer.publish(event).get(Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        // Consume.
        Set<String> receivedIds = new HashSet<>();
        try (KafkaConsumer<String, PaymentEvent> consumer = newConsumer()) {
            consumer.subscribe(List.of(EventDomain.FRAUD.rawTopic()));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                ConsumerRecords<String, PaymentEvent> batch = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, PaymentEvent> record : batch) {
                    receivedIds.add(record.value().getEventId());
                    assertThat(record.key()).isEqualTo(record.value().getEventId());
                }
                return receivedIds.size() >= expectedIds.size();
            });
        }

        assertThat(receivedIds).isEqualTo(expectedIds);
    }

    private static KafkaConsumer<String, PaymentEvent> newConsumer() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "conclave-it-consumer");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        config.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, REGISTRY_URL);
        config.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        return new KafkaConsumer<>(config);
    }
}
