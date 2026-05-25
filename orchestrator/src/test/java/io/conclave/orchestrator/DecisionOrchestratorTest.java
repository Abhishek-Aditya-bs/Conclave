package io.conclave.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.conclave.deliberation.proto.DeliberationRequest;
import io.conclave.events.fraud.EnrichedPaymentEvent;
import io.conclave.ingest.EventDomain;
import io.conclave.ingest.IngestProperties;
import io.conclave.orchestrator.client.DeliberationClient;
import io.conclave.orchestrator.domain.ContributingFactorRecord;
import io.conclave.orchestrator.domain.DecisionRecord;
import io.conclave.orchestrator.encode.DeliberationRequestTranslator;
import io.conclave.orchestrator.messaging.DecisionPublisher;
import io.conclave.orchestrator.messaging.DlqPublisher;
import io.conclave.orchestrator.messaging.DlqPublisher.FailureReason;
import io.conclave.orchestrator.storage.DecisionRepository;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DecisionOrchestratorTest {

    @Mock DeliberationRequestTranslator translator;
    @Mock DeliberationClient deliberationClient;
    @Mock DecisionRepository decisionRepository;
    @Mock DecisionPublisher decisionPublisher;
    @Mock DlqPublisher dlqPublisher;

    DecisionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        IngestProperties props = new IngestProperties(EventDomain.FRAUD, 3, (short) 1);
        orchestrator = new DecisionOrchestrator(
                translator, deliberationClient, decisionRepository,
                decisionPublisher, dlqPublisher, props);
    }

    private EnrichedPaymentEvent enriched(String eventId) {
        return EnrichedPaymentEvent.newBuilder()
                .setEventId(eventId)
                .setTimestamp(Instant.now())
                .setCardholderId("c").setCardToken("t").setAmountMinor(1L)
                .setCurrency("USD").setMerchantId("m").setMerchantCategoryCode(1)
                .setBin("4").setDeviceFingerprint("d").setIpAddress("i")
                .setBillingCountry("US").setShippingCountry(null)
                .setCardPresent(true).setChannel("WEB")
                .setCardholderVelocity(0L).setBinRiskScore(0.0)
                .setBaselineEntityId("c").setGraphEntityIds(List.of("c"))
                .setFeatureExtractedAt(Instant.now())
                .build();
    }

    private DeliberationRequest stubRequest(String eventId) {
        return DeliberationRequest.newBuilder()
                .setEventId(eventId).setDomain("fraud")
                .setBaselineEntityId("c").addGraphEntityIds("c")
                .setEnrichedEventJson("{\"eventId\":\"" + eventId + "\"}")
                .build();
    }

    private DecisionRecord stubDecision(String eventId) {
        return new DecisionRecord(
                UUID.randomUUID(), eventId, "fraud", 0.83, "BLOCK", "Block.",
                List.of(new ContributingFactorRecord("graph_ring_detected", 0.8, "ring")),
                240L, "anthropic", "claude-haiku-4-5-20251001",
                "{\"eventId\":\"" + eventId + "\"}", Instant.now());
    }

    @Test
    void happy_path_persists_then_publishes() {
        EnrichedPaymentEvent event = enriched("evt-1");
        when(translator.toRequest(EventDomain.FRAUD, event)).thenReturn(stubRequest("evt-1"));
        when(deliberationClient.deliberate(any())).thenReturn(stubDecision("evt-1"));

        orchestrator.process(event);

        verify(decisionRepository).save(any(DecisionRecord.class));
        verify(decisionPublisher).publish(any(DecisionRecord.class));
        verify(dlqPublisher, never()).publish(anyString(), any(), anyString(), anyString());
    }

    @Test
    void translation_failure_routes_to_dlq_no_m5_call() {
        EnrichedPaymentEvent event = enriched("evt-bad");
        when(translator.toRequest(EventDomain.FRAUD, event))
                .thenThrow(new IllegalArgumentException("missing field 'baselineEntityId'"));

        orchestrator.process(event);

        verify(deliberationClient, never()).deliberate(any());
        verify(decisionRepository, never()).save(any());
        verify(decisionPublisher, never()).publish(any());

        ArgumentCaptor<FailureReason> reason = ArgumentCaptor.forClass(FailureReason.class);
        verify(dlqPublisher).publish(eq("evt-bad"), reason.capture(),
                anyString(), eq(""));
        assertThat(reason.getValue()).isEqualTo(FailureReason.TRANSLATION_FAILURE);
    }

    @Test
    void m5_timeout_routes_to_dlq() {
        EnrichedPaymentEvent event = enriched("evt-1");
        when(translator.toRequest(EventDomain.FRAUD, event)).thenReturn(stubRequest("evt-1"));
        when(deliberationClient.deliberate(any()))
                .thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());

        orchestrator.process(event);

        verify(decisionRepository, never()).save(any());
        verify(decisionPublisher, never()).publish(any());
        ArgumentCaptor<FailureReason> reason = ArgumentCaptor.forClass(FailureReason.class);
        verify(dlqPublisher).publish(eq("evt-1"), reason.capture(), anyString(), anyString());
        assertThat(reason.getValue()).isEqualTo(FailureReason.M5_TIMEOUT);
    }

    @Test
    void m5_unavailable_routes_to_dlq() {
        EnrichedPaymentEvent event = enriched("evt-1");
        when(translator.toRequest(EventDomain.FRAUD, event)).thenReturn(stubRequest("evt-1"));
        when(deliberationClient.deliberate(any()))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());

        orchestrator.process(event);

        ArgumentCaptor<FailureReason> reason = ArgumentCaptor.forClass(FailureReason.class);
        verify(dlqPublisher).publish(eq("evt-1"), reason.capture(), anyString(), anyString());
        assertThat(reason.getValue()).isEqualTo(FailureReason.M5_UNAVAILABLE);
    }

    @Test
    void m5_internal_routes_to_dlq() {
        EnrichedPaymentEvent event = enriched("evt-1");
        when(translator.toRequest(EventDomain.FRAUD, event)).thenReturn(stubRequest("evt-1"));
        when(deliberationClient.deliberate(any()))
                .thenThrow(Status.INTERNAL.asRuntimeException());

        orchestrator.process(event);

        ArgumentCaptor<FailureReason> reason = ArgumentCaptor.forClass(FailureReason.class);
        verify(dlqPublisher).publish(eq("evt-1"), reason.capture(), anyString(), anyString());
        assertThat(reason.getValue()).isEqualTo(FailureReason.M5_INTERNAL);
    }

    @Test
    void persistence_failure_still_emits_decision_and_records_dlq() {
        EnrichedPaymentEvent event = enriched("evt-1");
        DecisionRecord decision = stubDecision("evt-1");
        when(translator.toRequest(EventDomain.FRAUD, event)).thenReturn(stubRequest("evt-1"));
        when(deliberationClient.deliberate(any())).thenReturn(decision);
        doThrow(new RuntimeException("connection refused")).when(decisionRepository).save(any());

        orchestrator.process(event);

        // We still emit on the success topic — downstream consumers shouldn't
        // be starved by a transient DB outage.
        verify(decisionPublisher).publish(any(DecisionRecord.class));
        // AND we record a DLQ entry so the audit replay can backfill the row.
        ArgumentCaptor<FailureReason> reason = ArgumentCaptor.forClass(FailureReason.class);
        verify(dlqPublisher).publish(eq("evt-1"), reason.capture(), anyString(), anyString());
        assertThat(reason.getValue()).isEqualTo(FailureReason.PERSISTENCE_FAILURE);
    }

    @Test
    void unexpected_m5_exception_routes_to_dlq() {
        EnrichedPaymentEvent event = enriched("evt-1");
        when(translator.toRequest(EventDomain.FRAUD, event)).thenReturn(stubRequest("evt-1"));
        when(deliberationClient.deliberate(any()))
                .thenThrow(new RuntimeException("unexpected"));

        orchestrator.process(event);

        ArgumentCaptor<FailureReason> reason = ArgumentCaptor.forClass(FailureReason.class);
        verify(dlqPublisher).publish(eq("evt-1"), reason.capture(), anyString(), anyString());
        assertThat(reason.getValue()).isEqualTo(FailureReason.UNEXPECTED);
    }

    @Test
    void classify_maps_status_codes() {
        assertThat(DecisionOrchestrator.classify(Status.DEADLINE_EXCEEDED))
                .isEqualTo(FailureReason.M5_TIMEOUT);
        assertThat(DecisionOrchestrator.classify(Status.UNAVAILABLE))
                .isEqualTo(FailureReason.M5_UNAVAILABLE);
        assertThat(DecisionOrchestrator.classify(Status.INTERNAL))
                .isEqualTo(FailureReason.M5_INTERNAL);
        // Other codes bucket as M5_INTERNAL — alerts fire on anything non-timeout/availability.
        assertThat(DecisionOrchestrator.classify(Status.INVALID_ARGUMENT))
                .isEqualTo(FailureReason.M5_INTERNAL);
    }

    @Test
    void publish_failure_after_persist_routes_to_dlq() {
        EnrichedPaymentEvent event = enriched("evt-1");
        DecisionRecord decision = stubDecision("evt-1");
        when(translator.toRequest(EventDomain.FRAUD, event)).thenReturn(stubRequest("evt-1"));
        when(deliberationClient.deliberate(any())).thenReturn(decision);
        doThrow(new RuntimeException("broker down")).when(decisionPublisher).publish(any());

        orchestrator.process(event);

        verify(decisionRepository).save(any());
        ArgumentCaptor<FailureReason> reason = ArgumentCaptor.forClass(FailureReason.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(dlqPublisher).publish(eq("evt-1"), reason.capture(), detail.capture(), anyString());
        assertThat(reason.getValue()).isEqualTo(FailureReason.UNEXPECTED);
        assertThat(detail.getValue()).contains("persisted=true").contains("broker down");
    }
}
