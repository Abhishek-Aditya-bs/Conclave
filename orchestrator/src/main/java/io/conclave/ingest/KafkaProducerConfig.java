package io.conclave.ingest;

import org.apache.avro.specific.SpecificRecord;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Declares a strongly-typed {@code KafkaTemplate<String, SpecificRecord>} bean that the
 * {@link KafkaEventProducer} can inject directly.
 *
 * <p>Why this exists: Spring Boot's Kafka auto-configuration registers a
 * {@code KafkaTemplate<?, ?>} bean, which Spring's type-aware DI won't match against a
 * parameterized request like {@code KafkaTemplate<String, SpecificRecord>}. By declaring
 * our own factory + template pair we keep the producer code type-safe; the
 * {@code @ConditionalOnMissingBean(KafkaTemplate.class)} on the auto-config makes our
 * declaration win without further configuration.
 */
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, SpecificRecord> avroProducerFactory(KafkaProperties properties) {
        return new DefaultKafkaProducerFactory<>(properties.buildProducerProperties());
    }

    @Bean
    public KafkaTemplate<String, SpecificRecord> kafkaTemplate(
            ProducerFactory<String, SpecificRecord> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
