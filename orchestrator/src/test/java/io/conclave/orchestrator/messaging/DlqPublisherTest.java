package io.conclave.orchestrator.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conclave.ingest.EventDomain;
import io.conclave.ingest.IngestProperties;
import io.conclave.orchestrator.messaging.DlqPublisher.FailureReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class DlqPublisherTest {

    @Mock KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void publishes_failure_payload_to_failed_topic() throws Exception {
        IngestProperties props = new IngestProperties(EventDomain.FRAUD, 3, (short) 1);
        DlqPublisher publisher = new DlqPublisher(kafkaTemplate, props);

        publisher.publish("evt-1", FailureReason.M5_TIMEOUT,
                "deadline exceeded after 1500ms",
                "{\"eventId\":\"evt-1\"}");

        ArgumentCaptor<String> topicCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCap = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCap.capture(), anyString(), payloadCap.capture());

        assertThat(topicCap.getValue()).isEqualTo("decisions.fraud.failed");

        JsonNode payload = new ObjectMapper().readTree(payloadCap.getValue());
        assertThat(payload.get("event_id").asText()).isEqualTo("evt-1");
        assertThat(payload.get("reason").asText()).isEqualTo("m5_timeout");
        assertThat(payload.get("detail").asText()).contains("deadline exceeded");
        assertThat(payload.has("failed_at_epoch_ms")).isTrue();
        assertThat(payload.get("enriched_event_json").asText()).contains("evt-1");
    }

    @Test
    void truncates_long_detail_field() {
        IngestProperties props = new IngestProperties(EventDomain.FRAUD, 3, (short) 1);
        DlqPublisher publisher = new DlqPublisher(kafkaTemplate, props);

        String huge = "x".repeat(10_000);
        publisher.publish("e", FailureReason.UNEXPECTED, huge, "");

        ArgumentCaptor<String> payloadCap = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), anyString(), payloadCap.capture());

        // Truncated to 4 KB to keep the DLQ topic bounded.
        // We can't assert exact length via JSON because escaping varies; assert the
        // payload size stays well within 5 KB instead.
        assertThat(payloadCap.getValue().length()).isLessThan(5_000);
    }

    @Test
    void null_enriched_event_json_becomes_empty_string() throws Exception {
        IngestProperties props = new IngestProperties(EventDomain.SECURITY, 3, (short) 1);
        DlqPublisher publisher = new DlqPublisher(kafkaTemplate, props);

        publisher.publish("e", FailureReason.TRANSLATION_FAILURE, "bad schema", null);

        ArgumentCaptor<String> payloadCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> topicCap = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCap.capture(), anyString(), payloadCap.capture());

        assertThat(topicCap.getValue()).isEqualTo("decisions.security.failed");
        JsonNode payload = new ObjectMapper().readTree(payloadCap.getValue());
        assertThat(payload.get("enriched_event_json").asText()).isEmpty();
        assertThat(payload.get("reason").asText()).isEqualTo("translation_failure");
    }

    @Test
    void failure_reason_codes_are_stable() {
        // Alerts depend on these codes; they should not churn silently.
        assertThat(FailureReason.M5_TIMEOUT.code()).isEqualTo("m5_timeout");
        assertThat(FailureReason.M5_INTERNAL.code()).isEqualTo("m5_internal");
        assertThat(FailureReason.M5_UNAVAILABLE.code()).isEqualTo("m5_unavailable");
        assertThat(FailureReason.PERSISTENCE_FAILURE.code()).isEqualTo("persistence_failure");
        assertThat(FailureReason.TRANSLATION_FAILURE.code()).isEqualTo("translation_failure");
        assertThat(FailureReason.UNEXPECTED.code()).isEqualTo("unexpected");
    }
}
