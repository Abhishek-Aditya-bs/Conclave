package io.conclave.orchestrator.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conclave.orchestrator.domain.ContributingFactorRecord;
import io.conclave.orchestrator.domain.DecisionRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class JdbcDecisionRepositoryTest {

    @Mock JdbcTemplate jdbc;

    @Test
    void save_emits_jsonb_castable_factors_payload() throws Exception {
        // JdbcTemplate.update returns int; the default 0 is fine — we're
        // asserting on what got SENT, not on what came back. Stubbing the
        // varargs method explicitly in strict mode is more trouble than the
        // assertion is worth.
        JdbcDecisionRepository repo = new JdbcDecisionRepository(jdbc);

        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-05-26T01:00:00Z");
        DecisionRecord d = new DecisionRecord(
                id, "evt-1", "fraud", 0.83, "BLOCK",
                "Block — ring.",
                List.of(
                        new ContributingFactorRecord("graph_ring_detected", 0.8, "Device touched 7"),
                        new ContributingFactorRecord("high_velocity", 0.3, "v=17")),
                240L, "anthropic", "claude-haiku-4-5-20251001",
                "{\"eventId\":\"evt-1\"}", now);

        repo.save(d);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), captor.capture());

        // Postgres JSONB needs the explicit ?::jsonb cast — without it the
        // driver sends text and the column type rejects.
        assertThat(sqlCaptor.getValue()).contains("?::jsonb");

        Object[] params = captor.getValue();
        assertThat(params[0]).isEqualTo(id);
        assertThat(params[1]).isEqualTo("evt-1");
        assertThat(params[2]).isEqualTo("fraud");
        assertThat(params[3]).isEqualTo(0.83);
        assertThat(params[4]).isEqualTo("BLOCK");
        assertThat(params[5]).isEqualTo("Block — ring.");

        // params[6] is the JSON-serialized contributing_factors list.
        String factorsJson = (String) params[6];
        var root = new ObjectMapper().readTree(factorsJson);
        assertThat(root.isArray()).isTrue();
        assertThat(root).hasSize(2);
        assertThat(root.get(0).get("name").asText()).isEqualTo("graph_ring_detected");
        assertThat(root.get(0).get("weight").asDouble()).isEqualTo(0.8);
        assertThat(root.get(0).get("evidence").asText()).isEqualTo("Device touched 7");

        assertThat(params[7]).isEqualTo(240L);
        assertThat(params[8]).isEqualTo("anthropic");
        assertThat(params[9]).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(params[10]).isEqualTo("{\"eventId\":\"evt-1\"}");
        assertThat(params[11]).isEqualTo(Timestamp.from(now));
    }
}
