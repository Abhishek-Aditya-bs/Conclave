package io.conclave.ingest;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the {@link NewTopic} beans for the active domain. Spring Kafka's auto-configured
 * {@code KafkaAdmin} picks these up and creates the topics on startup (idempotently).
 *
 * <p>Raw and enriched topics are declared here (the latter is the feature-extraction sink).
 * The decisions topic will be added by its owning module.
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

    @Bean
    public NewTopic enrichedTopic(IngestProperties properties) {
        // Match the raw topic's partition count so the feature-extraction stream's re-keying
        // doesn't introduce skew. (Kafka Streams will auto-create internal repartition
        // topics with this same count.)
        return TopicBuilder.name(properties.domain().enrichedTopic())
                .partitions(properties.rawTopicPartitions())
                .replicas(properties.replicationFactor())
                .build();
    }
}
