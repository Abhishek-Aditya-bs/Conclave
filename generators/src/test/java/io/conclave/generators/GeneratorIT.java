package io.conclave.generators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conclave.events.fraud.PaymentEvent;
import io.conclave.events.security.AuthEvent;
import io.conclave.generators.fraud.FraudGeneratorMain;
import io.conclave.generators.security.SecurityGeneratorMain;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end IT for both generators. Boots a real Testcontainers Kafka, drives the
 * planners directly (no shell-out), and asserts each raw event has a matching label
 * row on the side topic, keyed identically.
 *
 * <p>Uses a {@code mock://} Confluent schema registry so we don't need a Schema Registry
 * container. One IT class for both domains because the per-class JVM fork dominates
 * runtime; the two flows are independent within the test.
 */
@Testcontainers
class GeneratorIT {

    private static final String REGISTRY_URL = "mock://generators-it";

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("fraud generator: every raw event has a matching label row on the side topic")
    void fraudEndToEnd() throws Exception {
        runGenerator(GeneratorDomain.FRAUD, "--clean", "20", "--rings", "2", "--ato", "1", "--extra", "0");
        Set<String> rawIds = consumeAvroEventIds(GeneratorDomain.FRAUD.rawTopic());
        Map<String, JsonNode> labels = consumeLabels(GeneratorDomain.FRAUD.labelsTopic(), rawIds.size());

        assertThat(rawIds).isNotEmpty();
        assertThat(labels.keySet()).isEqualTo(rawIds);
        // At least one card-testing-ring event present (proves adversarial scenarios run).
        assertThat(labels.values()).anyMatch(n -> "CARD_TESTING_RING".equals(n.get("label").asText()));
        assertThat(labels.values()).anyMatch(n -> "FRAUD_ATO".equals(n.get("label").asText()));
        assertThat(labels.values()).anyMatch(n -> "CLEAN".equals(n.get("label").asText()));
        assertThat(labels.values()).allMatch(n -> "fraud".equals(n.get("domain").asText()));
    }

    @Test
    @DisplayName("security generator: every raw event has a matching label row on the side topic")
    void securityEndToEnd() throws Exception {
        runGenerator(GeneratorDomain.SECURITY, "--clean", "20", "--rings", "1", "--ato", "1", "--extra", "1");
        Set<String> rawIds = consumeAvroEventIds(GeneratorDomain.SECURITY.rawTopic());
        Map<String, JsonNode> labels = consumeLabels(GeneratorDomain.SECURITY.labelsTopic(), rawIds.size());

        assertThat(rawIds).isNotEmpty();
        assertThat(labels.keySet()).isEqualTo(rawIds);
        assertThat(labels.values()).anyMatch(n -> "LATERAL_MOVEMENT".equals(n.get("label").asText()));
        assertThat(labels.values()).anyMatch(n -> "EXFILTRATION".equals(n.get("label").asText()));
        assertThat(labels.values()).anyMatch(n -> "SECURITY_ATO".equals(n.get("label").asText()));
        assertThat(labels.values()).allMatch(n -> "security".equals(n.get("domain").asText()));
    }

    private void runGenerator(GeneratorDomain domain, String... args) {
        CliOptions.Defaults defaults = domain == GeneratorDomain.FRAUD
                ? FraudGeneratorMain.DEFAULTS
                : SecurityGeneratorMain.DEFAULTS;
        CliOptions opts = CliOptions.parse(args, defaults);
        Random random = new Random(opts.seed());
        Instant baseTime = Instant.now();
        List<Scenario> scenarios = domain == GeneratorDomain.FRAUD
                ? FraudGeneratorMain.planScenarios(opts, random, baseTime)
                : SecurityGeneratorMain.planScenarios(opts, random, baseTime);
        try (Producer<String, SpecificRecord> raw = avroProducer();
             Producer<String, String> labels = stringProducer();
             EventPublisher publisher = new EventPublisher(
                     domain, raw, labels, java.time.Clock.systemUTC())) {
            new GeneratorRunner(publisher).run(scenarios);
        }
    }

    private static Producer<String, SpecificRecord> avroProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "io.confluent.kafka.serializers.KafkaAvroSerializer");
        props.put("schema.registry.url", REGISTRY_URL);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(props);
    }

    private static Producer<String, String> stringProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(props);
    }

    private static Set<String> consumeAvroEventIds(String topic) {
        Set<String> ids = new HashSet<>();
        try (KafkaConsumer<String, SpecificRecord> consumer = newAvroConsumer()) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                ConsumerRecords<String, SpecificRecord> batch = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, SpecificRecord> r : batch) {
                    String eventId = extractEventId(r.value());
                    ids.add(eventId);
                }
                // Two empty polls in a row → drained.
                return !ids.isEmpty() && batch.isEmpty();
            });
        }
        return ids;
    }

    private Map<String, JsonNode> consumeLabels(String topic, int expectedCount) throws Exception {
        Map<String, JsonNode> out = new HashMap<>();
        try (KafkaConsumer<String, String> consumer = newStringConsumer()) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : batch) {
                    out.put(r.key(), mapper.readTree(r.value()));
                }
                return out.size() >= expectedCount && batch.isEmpty();
            });
        }
        return out;
    }

    private static KafkaConsumer<String, SpecificRecord> newAvroConsumer() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "generators-it-raw-" + System.nanoTime());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        config.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, REGISTRY_URL);
        config.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        return new KafkaConsumer<>(config);
    }

    private static KafkaConsumer<String, String> newStringConsumer() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "generators-it-labels-" + System.nanoTime());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(config);
    }

    private static String extractEventId(SpecificRecord record) {
        if (record instanceof PaymentEvent pe) {
            return pe.getEventId();
        }
        if (record instanceof AuthEvent ae) {
            return ae.getEventId();
        }
        throw new AssertionError("unknown record type: " + record.getClass());
    }
}
