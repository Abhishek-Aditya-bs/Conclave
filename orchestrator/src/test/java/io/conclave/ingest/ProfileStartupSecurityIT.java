package io.conclave.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.ConclaveApplication;
import io.conclave.events.security.AuthEvent;
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
 * Profile-startup integration test for the {@code security} profile. Mirror of
 * {@link ProfileStartupFraudIT} — same shape, different schema.
 */
@Testcontainers
@SpringBootTest(classes = ConclaveApplication.class)
@ActiveProfiles("security")
class ProfileStartupSecurityIT {

    private static final String REGISTRY_URL = "mock://conclave-startup-security";

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
    @DisplayName("security profile binds IngestProperties.domain to SECURITY")
    void bindsSecurityDomain() {
        assertThat(properties.domain()).isEqualTo(EventDomain.SECURITY);
    }

    @Test
    @DisplayName("security profile declares the events.security.raw NewTopic bean")
    void declaresRawTopicBean() {
        assertThat(rawTopic.name()).isEqualTo("events.security.raw");
        assertThat(rawTopic.numPartitions()).isEqualTo(properties.rawTopicPartitions());
    }

    @Test
    @DisplayName("security profile actually creates events.security.raw on the broker at startup")
    void createsRawTopicOnBroker() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            assertThat(admin.listTopics().names().get())
                    .contains(EventDomain.SECURITY.rawTopic());
        }
    }

    @Test
    @DisplayName("security profile producer publishes an AuthEvent that round-trips via the broker")
    void producerEndToEnd() throws Exception {
        AuthEvent event = EventFactory.randomAuthEvent();
        producer.publish(event).get(10, java.util.concurrent.TimeUnit.SECONDS);

        try (KafkaConsumer<String, AuthEvent> consumer = newConsumer()) {
            consumer.subscribe(List.of(EventDomain.SECURITY.rawTopic()));
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
            AuthEvent received = null;
            while (System.currentTimeMillis() < deadline && received == null) {
                ConsumerRecords<String, AuthEvent> batch = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, AuthEvent> r : batch) {
                    if (event.getEventId().equals(r.value().getEventId())) {
                        received = r.value();
                        break;
                    }
                }
            }
            assertThat(received).as("event not received within 20s").isNotNull();
            assertThat(received.getPrincipalId()).isEqualTo(event.getPrincipalId());
            assertThat(received.getAuthMethod()).isEqualTo(event.getAuthMethod());
        }
    }

    private static KafkaConsumer<String, AuthEvent> newConsumer() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "conclave-startup-security-consumer");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        config.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, REGISTRY_URL);
        config.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        return new KafkaConsumer<>(config);
    }
}
