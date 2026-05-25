package io.conclave.orchestrator.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conclave.ingest.IngestProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes a failure payload to {@code decisions.{domain}.failed} when
 * the orchestrator can't produce a Decision (M5 timeout, M5 INTERNAL,
 * Postgres write failure, malformed event).
 *
 * <p>The DLQ payload is JSON with three fields:
 * <ul>
 *   <li>{@code event_id} — for log/audit correlation.</li>
 *   <li>{@code reason} — short, machine-readable category
 *       ({@code "m5_timeout"}, {@code "m5_internal"}, {@code "persistence_failure"},
 *       {@code "translation_failure"}). See {@link FailureReason}.</li>
 *   <li>{@code detail} — free-form error message; truncated to 4 KB to
 *       keep the topic bounded.</li>
 *   <li>{@code failed_at_epoch_ms} — wall-clock at failure time.</li>
 *   <li>{@code enriched_event_json} — verbatim of what the orchestrator
 *       received. Empty string if the failure happened before
 *       translation (e.g. the upstream event itself was malformed).</li>
 * </ul>
 *
 * <p>Why JSON not Avro: same reason as {@link DecisionPublisher} — the
 * DLQ consumer is a one-off replay tool, JSON is more inspectable.
 */
@Component
@ConditionalOnProperty(name = "conclave.orchestrator.enabled", havingValue = "true",
        matchIfMissing = true)
public class DlqPublisher {

    private static final int MAX_DETAIL_LENGTH = 4096;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final IngestProperties ingestProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DlqPublisher(
            KafkaTemplate<String, String> decisionsKafkaTemplate,
            IngestProperties ingestProperties) {
        this.kafkaTemplate = decisionsKafkaTemplate;
        this.ingestProperties = ingestProperties;
    }

    public void publish(
            String eventId,
            FailureReason reason,
            String detail,
            String enrichedEventJson) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("event_id", eventId);
        map.put("reason", reason.code());
        map.put("detail", truncate(detail));
        map.put("failed_at_epoch_ms", Instant.now().toEpochMilli());
        map.put("enriched_event_json", enrichedEventJson == null ? "" : enrichedEventJson);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            // Last-ditch: emit a hand-built JSON so we don't lose the event in the
            // event of a Jackson glitch. Don't let DLQ publishing itself fail loudly.
            payload = "{\"event_id\":\"" + escape(eventId)
                    + "\",\"reason\":\"" + reason.code()
                    + "\",\"detail\":\"jackson failure: " + escape(e.getMessage()) + "\"}";
        }
        kafkaTemplate.send(ingestProperties.domain().decisionsFailedTopic(), eventId, payload);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_DETAIL_LENGTH ? s : s.substring(0, MAX_DETAIL_LENGTH);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Short, stable failure categories. Don't churn these — alerts depend on them. */
    public enum FailureReason {
        M5_TIMEOUT("m5_timeout"),
        M5_INTERNAL("m5_internal"),
        M5_UNAVAILABLE("m5_unavailable"),
        PERSISTENCE_FAILURE("persistence_failure"),
        TRANSLATION_FAILURE("translation_failure"),
        UNEXPECTED("unexpected");

        private final String code;

        FailureReason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
