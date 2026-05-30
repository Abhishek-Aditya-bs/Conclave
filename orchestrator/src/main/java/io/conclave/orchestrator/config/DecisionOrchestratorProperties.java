package io.conclave.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the decision orchestrator. Bound from
 * {@code conclave.orchestrator.*} in YAML.
 *
 * <p>Fail-fast in the compact constructor — same pattern as
 * {@link io.conclave.ingest.IngestProperties}. We deliberately don't
 * pull in {@code spring-boot-starter-validation} for two scalar
 * validations.
 *
 * @param deliberationTarget gRPC target for the deliberation service,
 *                           e.g. {@code dns:///localhost:9093} or {@code host:port}.
 * @param deliberationDeadlineMs per-call deadline in ms for the deliberation
 *                               RPC. The LLM judge can take tens of seconds
 *                               (local CPU Ollama: 60-90s; cloud models often
 *                               &gt; 1.5s), so the default is 30s
 *                               (overridable via {@code DELIBERATION_DEADLINE_MS}).
 *                               A too-short deadline cancels the call before the
 *                               judge responds, so no decision is persisted.
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
