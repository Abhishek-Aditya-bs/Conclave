package io.conclave.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the M6 decision orchestrator. Bound from
 * {@code conclave.orchestrator.*} in YAML.
 *
 * <p>Fail-fast in the compact constructor — same pattern as
 * {@link io.conclave.ingest.IngestProperties}. We deliberately don't
 * pull in {@code spring-boot-starter-validation} for two scalar
 * validations.
 *
 * @param deliberationTarget gRPC target for the M5 deliberation service,
 *                           e.g. {@code dns:///localhost:9093} or {@code host:port}.
 * @param deliberationDeadlineMs per-call deadline in ms for the deliberation
 *                               RPC. p99 budget on the Anthropic backend is
 *                               600ms (spec §6 M5); 1500ms gives ~2.5×
 *                               headroom for one retry within the M6 750ms
 *                               end-to-end target.
 * @param kafkaConsumerGroupId stable group id for the @KafkaListener; one
 *                             logical consumer per orchestrator deployment.
 * @param decisionsTopicPartitions partition count for the
 *                                 {@code decisions.{domain}} output topic.
 *                                 Use the same partition count as the
 *                                 enriched-input topic to preserve order.
 */
@ConfigurationProperties("conclave.orchestrator")
public record DecisionOrchestratorProperties(
        String deliberationTarget,
        long deliberationDeadlineMs,
        String kafkaConsumerGroupId,
        int decisionsTopicPartitions
) {
    public DecisionOrchestratorProperties {
        if (deliberationTarget == null || deliberationTarget.isBlank()) {
            throw new IllegalArgumentException(
                    "conclave.orchestrator.deliberation-target is required");
        }
        if (deliberationDeadlineMs <= 0) {
            throw new IllegalArgumentException(
                    "conclave.orchestrator.deliberation-deadline-ms must be > 0, got "
                            + deliberationDeadlineMs);
        }
        if (kafkaConsumerGroupId == null || kafkaConsumerGroupId.isBlank()) {
            throw new IllegalArgumentException(
                    "conclave.orchestrator.kafka-consumer-group-id is required");
        }
        if (decisionsTopicPartitions <= 0) {
            throw new IllegalArgumentException(
                    "conclave.orchestrator.decisions-topic-partitions must be > 0, got "
                            + decisionsTopicPartitions);
        }
    }
}
