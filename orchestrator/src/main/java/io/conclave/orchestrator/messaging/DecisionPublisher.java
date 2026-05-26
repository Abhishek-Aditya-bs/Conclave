package io.conclave.orchestrator.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conclave.ingest.IngestProperties;
import io.conclave.orchestrator.domain.ContributingFactorRecord;
import io.conclave.orchestrator.domain.DecisionRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Serializes a {@link DecisionRecord} to JSON and emits it on
 * {@code decisions.{domain}}, keyed by event id so all decisions for a
 * given upstream event land on the same partition.
 *
 * <p>The wire JSON is intentionally flat — the consumers (dashboard,
 * benchmarks, downstream notification jobs) all want a single object
 * they can render or sort on without traversing nested structures.
 * {@code contributing_factors} stays as a JSON array (one nesting level)
 * because the dashboard wants to render it as a table.
 */
@Component
@ConditionalOnProperty(name = "conclave.orchestrator.enabled", havingValue = "true",
        matchIfMissing = true)
public class DecisionPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final IngestProperties ingestProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DecisionPublisher(
            KafkaTemplate<String, String> decisionsKafkaTemplate,
            IngestProperties ingestProperties) {
        this.kafkaTemplate = decisionsKafkaTemplate;
        this.ingestProperties = ingestProperties;
    }

    public void publish(DecisionRecord decision) {
        String payload = serialize(decision);
        String topic = ingestProperties.domain().decisionsTopic();
        kafkaTemplate.send(topic, decision.eventId(), payload);
    }

    private String serialize(DecisionRecord decision) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("decision_id", decision.decisionId().toString());
        map.put("event_id", decision.eventId());
        map.put("domain", decision.domain());
        map.put("baseline_entity_id", decision.baselineEntityId());
        map.put("score", decision.score());
        map.put("verdict_label", decision.verdictLabel());
        map.put("verdict_explanation_md", decision.verdictExplanationMd());
        map.put("contributing_factors", toFactorList(decision.contributingFactors()));
        map.put("latency_ms", decision.latencyMs());
        map.put("judge_provider", decision.judgeProvider());
        map.put("judge_model", decision.judgeModel());
        map.put("created_at_epoch_ms", decision.createdAt().toEpochMilli());
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize decision " + decision.decisionId(), e);
        }
    }

    private static List<Map<String, Object>> toFactorList(List<ContributingFactorRecord> factors) {
        List<Map<String, Object>> out = new ArrayList<>(factors.size());
        for (ContributingFactorRecord f : factors) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", f.name());
            map.put("weight", f.weight());
            map.put("evidence", f.evidence());
            out.add(map);
        }
        return out;
    }
}
