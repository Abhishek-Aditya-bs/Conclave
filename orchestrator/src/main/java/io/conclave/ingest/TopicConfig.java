package io.conclave.ingest;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the {@link NewTopic} beans for the active domain. Spring Kafka's auto-configured
 * {@code KafkaAdmin} picks these up and creates the topics on startup (idempotently).
 *
 * <p>Only the raw topic is declared in M1; enriched / decisions topics are declared by their
 * owning modules (M2, M6) when those land.
 */
@Configuration
public class TopicConfig {

    @Bean
    public NewTopic rawTopic(IngestProperties properties) {
        return TopicBuilder.name(properties.domain().rawTopic())
                .partitions(properties.rawTopicPartitions())
                .replicas(properties.replicationFactor())
                .build();
    }
}
