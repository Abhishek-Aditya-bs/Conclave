package io.conclave.orchestrator.messaging;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Producer wiring for the orchestrator's two output topics. Decision payloads are JSON
 * strings (not Avro) because:
 * <ul>
 *   <li>The dashboard and benchmark scripts both expect JSON, and
 *       defining a separate Avro schema for Decision just to re-serialize
 *       to JSON downstream is pure ceremony.</li>
 *   <li>The {@code judge_provider} + {@code judge_model} fields evolve
 *       with the LLM provider matrix; JSON's schema-on-read makes that
 *       evolution cheap.</li>
 * </ul>
 *
 * <p>This is in addition to the {@code KafkaTemplate<String, SpecificRecord>}
 * declared by {@code io.conclave.ingest.KafkaProducerConfig} for the raw
 * Avro path; both coexist because Spring's generic-aware DI distinguishes
 * them by their type parameters.
 */
@Configuration
@ConditionalOnProperty(name = "conclave.orchestrator.enabled", havingValue = "true",
        matchIfMissing = true)
public class DecisionKafkaConfig {

    @Bean
    public ProducerFactory<String, String> jsonProducerFactory(KafkaProperties properties) {
        // Start from the shared producer properties (bootstrap-servers, idempotence, acks)
        // then override the value serializer to String — Confluent's Avro serializer is
        // bound by the autoconfig yaml block and would explode on a String payload.
        Map<String, Object> config = new HashMap<>(properties.buildProducerProperties());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> decisionsKafkaTemplate(
            ProducerFactory<String, String> jsonProducerFactory) {
        return new KafkaTemplate<>(jsonProducerFactory);
    }
}
