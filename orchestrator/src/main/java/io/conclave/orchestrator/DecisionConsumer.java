package io.conclave.orchestrator;

import org.apache.avro.generic.IndexedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@link KafkaListener} that drives the orchestrator off
 * {@code events.{domain}.enriched}.
 *
 * <p>The topic name and consumer group id are SpEL-resolved against the
 * already-bound configuration beans — keeps the listener domain-agnostic
 * and means the active Spring profile (fraud or security) picks the
 * right topic without code branches.
 *
 * <p>Listener concurrency is left at the Spring default (1 per consumer
 * group instance). For higher throughput, bump
 * {@code spring.kafka.listener.concurrency} or the partition count on
 * the enriched topic — feature extraction already keys by {@code baselineEntityId} so
 * scaling partitions preserves per-entity ordering through the
 * pipeline.
 */
@Component
@ConditionalOnProperty(name = "conclave.orchestrator.enabled", havingValue = "true",
        matchIfMissing = true)
public class DecisionConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionConsumer.class);

    private final DecisionOrchestrator orchestrator;

    public DecisionConsumer(DecisionOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @KafkaListener(
            // String beans declared by DecisionTopicConfig — SpEL resolves them
            // by simple bean name. @ConfigurationProperties records register
            // under a verbose <prefix>-<FQCN> name that SpEL won't resolve
            // cleanly, so we route through these one-token beans.
            topics = "#{@enrichedInputTopic}",
            groupId = "#{@orchestratorConsumerGroup}",
            // Avro deserializer sometimes returns a SpecificRecord (typed) and
            // sometimes a GenericRecord (when specific.avro.reader=false). Both
            // satisfy IndexedRecord so we accept that and let the encoder /
            // translator handle the actual reading by schema field name.
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onEnrichedEvent(IndexedRecord record) {
        if (record == null) {
            LOG.warn("Received null enriched event; ignoring");
            return;
        }
        orchestrator.process(record);
    }
}
