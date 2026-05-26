package io.conclave.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.conclave.deliberation.proto.DeliberationRequest;
import io.conclave.orchestrator.client.DeliberationClient;
import io.conclave.orchestrator.domain.ContributingFactorRecord;
import io.conclave.orchestrator.domain.DecisionRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock DecisionAuditRepository repository;
    @Mock DeliberationClient deliberationClient;

    private AuditService service() {
        return new AuditService(repository, deliberationClient);
    }

    private DecisionRecord sample(String eventId, String enrichedJson) {
        return new DecisionRecord(
                UUID.randomUUID(), eventId, "fraud", "cardholder-9", 0.83, "BLOCK",
                "Block.",
                List.of(new ContributingFactorRecord("graph_ring_detected", 0.8, "ring")),
                240L, "anthropic", "claude-haiku-4-5-20251001",
                enrichedJson, Instant.parse("2026-05-26T01:00:00Z"));
    }

    @Test
    void list_returns_page_with_total() {
        DecisionFilter filter = DecisionFilter.builder().domain("fraud").build();
        when(repository.count(filter)).thenReturn(12L);
        DecisionSummary s = new DecisionSummary(
                UUID.randomUUID(), "e", "fraud", "c", 0.5, "REVIEW", 10L,
                "anthropic", "claude-haiku-4-5-20251001", Instant.now());
        when(repository.findAll(filter)).thenReturn(List.of(s));

        DecisionPage page = service().list(filter);

        assertThat(page.total()).isEqualTo(12L);
        assertThat(page.items()).containsExactly(s);
        assertThat(page.limit()).isEqualTo(filter.limit());
        assertThat(page.offset()).isEqualTo(filter.offset());
    }

    @Test
    void list_skips_items_query_when_total_zero() {
        DecisionFilter filter = DecisionFilter.empty();
        when(repository.count(filter)).thenReturn(0L);

        DecisionPage page = service().list(filter);

        assertThat(page.total()).isZero();
        assertThat(page.items()).isEmpty();
        verify(repository, never()).findAll(any());
    }

    @Test
    void detail_returns_record() {
        UUID id = UUID.randomUUID();
        DecisionRecord record = sample("e", "{}");
        when(repository.findById(id)).thenReturn(Optional.of(record));

        DecisionDetail detail = service().detail(id);

        assertThat(detail.eventId()).isEqualTo("e");
        assertThat(detail.judgeProvider()).isEqualTo("anthropic");
    }

    @Test
    void detail_throws_when_missing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().detail(id))
                .isInstanceOf(DecisionNotFoundException.class);
    }

    @Test
    void replay_rebuilds_request_and_does_not_persist() {
        UUID id = UUID.randomUUID();
        String enriched = """
                {
                  "eventId": "evt-1",
                  "baselineEntityId": "cardholder-9",
                  "graphEntityIds": ["cardholder-9", "dev-1", "1.2.3.4", "mer-1"]
                }
                """;
        DecisionRecord original = sample("evt-1", enriched);
        when(repository.findById(id)).thenReturn(Optional.of(original));

        DecisionRecord fresh = sample("evt-1", enriched);
        when(deliberationClient.deliberate(any(DeliberationRequest.class)))
                .thenReturn(fresh);

        DecisionDetail result = service().replay(id);

        ArgumentCaptor<DeliberationRequest> captor =
                ArgumentCaptor.forClass(DeliberationRequest.class);
        verify(deliberationClient).deliberate(captor.capture());
        DeliberationRequest sent = captor.getValue();
        assertThat(sent.getEventId()).isEqualTo("evt-1");
        assertThat(sent.getDomain()).isEqualTo("fraud");
        assertThat(sent.getBaselineEntityId()).isEqualTo("cardholder-9");
        assertThat(sent.getGraphEntityIdsList())
                .containsExactly("cardholder-9", "dev-1", "1.2.3.4", "mer-1");
        assertThat(sent.getEnrichedEventJson()).isEqualTo(enriched);

        assertThat(result.decisionId()).isEqualTo(fresh.decisionId());
    }

    @Test
    void replay_when_missing_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().replay(id))
                .isInstanceOf(DecisionNotFoundException.class);
        verify(deliberationClient, never()).deliberate(any());
    }

    @Test
    void replay_tolerates_missing_graph_entity_ids() {
        UUID id = UUID.randomUUID();
        DecisionRecord original = sample("evt-1", "{\"eventId\":\"evt-1\"}");
        when(repository.findById(id)).thenReturn(Optional.of(original));
        when(deliberationClient.deliberate(any())).thenReturn(original);

        service().replay(id);

        ArgumentCaptor<DeliberationRequest> captor =
                ArgumentCaptor.forClass(DeliberationRequest.class);
        verify(deliberationClient).deliberate(captor.capture());
        assertThat(captor.getValue().getGraphEntityIdsList()).isEmpty();
    }

    @Test
    void replay_tolerates_malformed_enriched_json() {
        UUID id = UUID.randomUUID();
        DecisionRecord original = sample("evt-1", "{not json");
        when(repository.findById(id)).thenReturn(Optional.of(original));
        when(deliberationClient.deliberate(any())).thenReturn(original);

        // Should not throw — replay continues with an empty graph_entity_ids list.
        service().replay(id);
        verify(deliberationClient).deliberate(any());
    }
}
