package io.conclave.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.ConclaveApplication;
import io.conclave.events.fraud.PaymentEvent;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
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
 * Profile-startup integration test for the {@code fraud} profile.
 *
 * <p>Verifies that the Spring application boots cleanly under {@code SPRING_PROFILES_ACTIVE=fraud},
 * that {@link IngestProperties} bind to {@link EventDomain#FRAUD}, that the raw topic gets
 * created with the configured partition count, and that one round-trip Avro send works.
 *
 * <p>Companion: {@link ProfileStartupSecurityIT}. Together they prove the M8 promise that
 * "both configurations boot from the same binary via env vars."
 */
@Testcontainers
@SpringBootTest(classes = ConclaveApplication.class)
@ActiveProfiles("fraud")
class ProfileStartupFraudIT {

    private static final String REGISTRY_URL = "mock://conclave-startup-fraud";

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.producer.properties.schema.registry.url", () -> REGISTRY_URL);
    }

    @Autowired IngestProperties properties;
    @Autowired EventProducer producer;
    @Autowired org.apache.kafka.clients.admin.NewTopic rawTopic;

    @Test
    @DisplayName("fraud profile binds IngestProperties.domain to FRAUD")
    void bindsFraudDomain() {
        assertThat(properties.domain()).isEqualTo(EventDomain.FRAUD);
    }

    @Test
    @DisplayName("fraud profile declares the events.fraud.raw NewTopic bean with configured partitions")
    void declaresRawTopicBean() {
        assertThat(rawTopic.name()).isEqualTo("events.fraud.raw");
        assertThat(rawTopic.numPartitions()).isEqualTo(properties.rawTopicPartitions());
    }

    @Test
    @DisplayName("fraud profile actually creates events.fraud.raw on the broker at startup")
    void createsRawTopicOnBroker() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            assertThat(admin.listTopics().names().get())
                    .contains(EventDomain.FRAUD.rawTopic());
        }
    }

    @Test
    @DisplayName("fraud profile producer publishes a PaymentEvent that round-trips via the broker")
    void producerEndToEnd() throws Exception {
        PaymentEvent event = EventFactory.randomPaymentEvent();
        producer.publish(event).get(10, java.util.concurrent.TimeUnit.SECONDS);

        try (KafkaConsumer<String, PaymentEvent> consumer = newConsumer()) {
            consumer.subscribe(List.of(EventDomain.FRAUD.rawTopic()));
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
            PaymentEvent received = null;
            while (System.currentTimeMillis() < deadline && received == null) {
                ConsumerRecords<String, PaymentEvent> batch = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, PaymentEvent> r : batch) {
                    if (event.getEventId().equals(r.value().getEventId())) {
                        received = r.value();
                        break;
                    }
                }
            }
            assertThat(received).as("event not received within 20s").isNotNull();
            assertThat(received.getCardholderId()).isEqualTo(event.getCardholderId());
        }
    }

    private static KafkaConsumer<String, PaymentEvent> newConsumer() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "conclave-startup-fraud-consumer");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        config.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, REGISTRY_URL);
        config.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        return new KafkaConsumer<>(config);
    }
}
