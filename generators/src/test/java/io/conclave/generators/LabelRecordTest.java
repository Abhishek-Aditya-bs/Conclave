package io.conclave.generators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LabelRecordTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("serializes to snake_case JSON in declared order")
    void snakeCaseJson() throws Exception {
        LabelRecord record = new LabelRecord(
                "evt-1", "fraud", Labels.CARD_TESTING_RING, "ring-3", "card-testing", 1700000000000L);
        String json = mapper.writeValueAsString(record);
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("event_id").asText()).isEqualTo("evt-1");
        assertThat(node.get("domain").asText()).isEqualTo("fraud");
        assertThat(node.get("label").asText()).isEqualTo("CARD_TESTING_RING");
        assertThat(node.get("scenario_id").asText()).isEqualTo("ring-3");
        assertThat(node.get("reason").asText()).isEqualTo("card-testing");
        assertThat(node.get("emitted_at_ms").asLong()).isEqualTo(1700000000000L);
    }

    @Test
    @DisplayName("null fields rejected in compact constructor")
    void nullsRejected() {
        assertThatThrownBy(() -> new LabelRecord(null, "fraud", Labels.CLEAN, "x", "y", 0L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LabelRecord("e", null, Labels.CLEAN, "x", "y", 0L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LabelRecord("e", "f", null, "x", "y", 0L))
                .isInstanceOf(NullPointerException.class);
    }
}
