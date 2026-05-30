package io.conclave.orchestrator.messaging;

import io.conclave.ingest.IngestProperties;
import io.conclave.orchestrator.config.DecisionOrchestratorProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/* Imports kept short above; the named-string beans below let
 * {@code @KafkaListener} SpEL reference them by simple bean name —
 * {@code @ConfigurationProperties} records register under a verbose
 * {@code <prefix>-<FQCN>} name that SpEL can't resolve cleanly. */

/**
 * Declares the two output topics the orchestrator owns:
 * <ul>
 *   <li>{@code decisions.{domain}} — every Decision the orchestrator produces.</li>
 *   <li>{@code decisions.{domain}.failed} — DLQ for events where the judge timed out,
 *       returned INTERNAL, or where Postgres persistence threw. Don't drop
 *       events; let a downstream tool replay or alert.</li>
 * </ul>
 *
 * <p>Partition count mirrors the raw topic ({@link IngestProperties#rawTopicPartitions()})
 * so downstream consumers see decisions in the same partition shape as the
 * upstream events. Replication factor follows {@code IngestProperties} too —
 * 1 for the demo, bumped in real deployments.
 */
@Configuration
@ConditionalOnProperty(name = "conclave.orchestrator.enabled", havingValue = "true",
        matchIfMissing = true)
public class DecisionTopicConfig {

    @Bean
    public NewTopic decisionsTopic(
            IngestProperties ingest, DecisionOrchestratorProperties orchestrator) {
        return TopicBuilder.name(ingest.domain().decisionsTopic())
                .partitions(orchestrator.decisionsTopicPartitions())
                .replicas(ingest.replicationFactor())
                .build();
    }

    @Bean
    public NewTopic decisionsFailedTopic(
            IngestProperties ingest, DecisionOrchestratorProperties orchestrator) {
        return TopicBuilder.name(ingest.domain().decisionsFailedTopic())
                .partitions(orchestrator.decisionsTopicPartitions())
                .replicas(ingest.replicationFactor())
                .build();
    }

    /**
     * String view of the enriched-input topic, named so SpEL on
     * {@code @KafkaListener(topics = "#{@enrichedInputTopic}")} can resolve it
     * with a one-token reference.
     */
    @Bean
    public String enrichedInputTopic(IngestProperties ingest) {
        return ingest.domain().enrichedTopic();
    }

    /** String view of the consumer group id for the same SpEL reason. */
    @Bean
    public String orchestratorConsumerGroup(DecisionOrchestratorProperties orchestrator) {
        return orchestrator.kafkaConsumerGroupId();
    }
}
