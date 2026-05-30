package io.conclave.generators;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/**
 * Wire format for the ground-truth label side topic. snake_case so it lines up with
 * the rest of the system's JSON payloads (orchestrator decisions, audit API).
 *
 * <p>{@code scenarioId} groups events that belong to the same adversarial campaign
 * (e.g. one card-testing ring). Useful for downstream eval grouping.
 */
@JsonPropertyOrder({"event_id", "domain", "label", "scenario_id", "reason", "emitted_at_ms"})
public record LabelRecord(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("domain") String domain,
        @JsonProperty("label") Labels label,
        @JsonProperty("scenario_id") String scenarioId,
        @JsonProperty("reason") String reason,
        @JsonProperty("emitted_at_ms") long emittedAtMs) {

    public LabelRecord {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(scenarioId, "scenarioId");
        Objects.requireNonNull(reason, "reason");
    }
}
