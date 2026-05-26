package io.conclave.orchestrator.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conclave.ingest.EventDomain;
import io.conclave.ingest.IngestProperties;
import io.conclave.orchestrator.domain.ContributingFactorRecord;
import io.conclave.orchestrator.domain.DecisionRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class DecisionPublisherTest {

    @Mock KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void publishes_json_keyed_by_event_id_to_domain_topic() throws Exception {
        IngestProperties props = new IngestProperties(EventDomain.FRAUD, 3, (short) 1);
        DecisionPublisher publisher = new DecisionPublisher(kafkaTemplate, props);

        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-05-26T01:00:00Z");
        DecisionRecord d = new DecisionRecord(
                id, "evt-1", "fraud", "cardholder-9", 0.83, "BLOCK", "Block.",
                List.of(new ContributingFactorRecord("graph_ring_detected", 0.8, "ring")),
                240L, "anthropic", "claude-haiku-4-5-20251001",
                "{}", now);

        publisher.publish(d);

        ArgumentCaptor<String> topicCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCap = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCap.capture(), keyCap.capture(), payloadCap.capture());

        assertThat(topicCap.getValue()).isEqualTo("decisions.fraud");
        // Keying by event_id keeps all decisions for an upstream event on the
        // same partition — important for downstream consumers that expect
        // sequential per-entity processing.
        assertThat(keyCap.getValue()).isEqualTo("evt-1");

        JsonNode payload = new ObjectMapper().readTree(payloadCap.getValue());
        assertThat(payload.get("decision_id").asText()).isEqualTo(id.toString());
        assertThat(payload.get("event_id").asText()).isEqualTo("evt-1");
        assertThat(payload.get("baseline_entity_id").asText()).isEqualTo("cardholder-9");
        assertThat(payload.get("verdict_label").asText()).isEqualTo("BLOCK");
        assertThat(payload.get("score").asDouble()).isEqualTo(0.83);
        assertThat(payload.get("contributing_factors").isArray()).isTrue();
        assertThat(payload.get("contributing_factors").get(0).get("name").asText())
                .isEqualTo("graph_ring_detected");
        assertThat(payload.get("judge_provider").asText()).isEqualTo("anthropic");
        assertThat(payload.get("judge_model").asText())
                .isEqualTo("claude-haiku-4-5-20251001");
        assertThat(payload.get("created_at_epoch_ms").asLong())
                .isEqualTo(now.toEpochMilli());
    }

    @Test
    void security_domain_uses_security_topic() {
        IngestProperties props = new IngestProperties(EventDomain.SECURITY, 3, (short) 1);
        DecisionPublisher publisher = new DecisionPublisher(kafkaTemplate, props);
        DecisionRecord d = new DecisionRecord(
                UUID.randomUUID(), "evt", "security", "alice@corp", 0.1, "ALLOW", "ok",
                List.of(new ContributingFactorRecord("no_anomaly_observed", 0.0, "fine")),
                10L, "ollama", "qwen3:8b", "{}", Instant.now());

        publisher.publish(d);

        ArgumentCaptor<String> topicCap = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCap.capture(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        assertThat(topicCap.getValue()).isEqualTo("decisions.security");
    }
}
