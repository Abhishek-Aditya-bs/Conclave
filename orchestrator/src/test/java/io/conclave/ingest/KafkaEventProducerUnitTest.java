package io.conclave.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.conclave.events.fraud.PaymentEvent;
import io.conclave.events.security.AuthEvent;
import java.util.concurrent.CompletableFuture;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Unit tests for {@link KafkaEventProducer}. Mocks {@link KafkaTemplate} so we don't need a
 * broker — these run in Surefire alongside the Avro round-trip tests and catch routing
 * regressions cheaply.
 */
class KafkaEventProducerUnitTest {

    @Test
    @DisplayName("publish() routes a PaymentEvent to events.fraud.raw under the fraud domain")
    void routesPaymentEventToFraudTopic() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, SpecificRecord> template = mock(KafkaTemplate.class);
        when(template.send(eq("events.fraud.raw"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        IngestProperties props = new IngestProperties(EventDomain.FRAUD, 3, (short) 1);
        KafkaEventProducer producer = new KafkaEventProducer(template, props);
        PaymentEvent event = EventFactory.randomPaymentEvent();

        producer.publish(event);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(template).send(topicCaptor.capture(), keyCaptor.capture(), eq(event));
        assertThat(topicCaptor.getValue()).isEqualTo("events.fraud.raw");
        assertThat(keyCaptor.getValue()).isEqualTo(event.getEventId());
    }

    @Test
    @DisplayName("publish() routes an AuthEvent to events.security.raw under the security domain")
    void routesAuthEventToSecurityTopic() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, SpecificRecord> template = mock(KafkaTemplate.class);
        when(template.send(eq("events.security.raw"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        IngestProperties props = new IngestProperties(EventDomain.SECURITY, 3, (short) 1);
        KafkaEventProducer producer = new KafkaEventProducer(template, props);
        AuthEvent event = EventFactory.randomAuthEvent();

        producer.publish(event);

        verify(template).send(eq("events.security.raw"), eq(event.getEventId()), eq(event));
    }

    @Test
    @DisplayName("publish() fails fast when event is null")
    void rejectsNullEvent() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, SpecificRecord> template = mock(KafkaTemplate.class);
        IngestProperties props = new IngestProperties(EventDomain.FRAUD, 3, (short) 1);
        KafkaEventProducer producer = new KafkaEventProducer(template, props);

        assertThatThrownBy(() -> producer.publish(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("event");
    }

    @Test
    @DisplayName("IngestProperties rejects a null domain")
    void ingestPropertiesRejectsNullDomain() {
        assertThatThrownBy(() -> new IngestProperties(null, 3, (short) 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conclave.ingest.domain");
    }

    @Test
    @DisplayName("IngestProperties rejects non-positive partition counts")
    void ingestPropertiesRejectsZeroPartitions() {
        assertThatThrownBy(() -> new IngestProperties(EventDomain.FRAUD, 0, (short) 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestProperties(EventDomain.FRAUD, 3, (short) 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("EventDomain.rawTopic() / enrichedTopic() / decisionsTopic() follow the convention")
    void eventDomainTopicNames() {
        assertThat(EventDomain.FRAUD.rawTopic()).isEqualTo("events.fraud.raw");
        assertThat(EventDomain.FRAUD.enrichedTopic()).isEqualTo("events.fraud.enriched");
        assertThat(EventDomain.FRAUD.decisionsTopic()).isEqualTo("decisions.fraud");
        assertThat(EventDomain.SECURITY.rawTopic()).isEqualTo("events.security.raw");
        assertThat(EventDomain.SECURITY.enrichedTopic()).isEqualTo("events.security.enriched");
        assertThat(EventDomain.SECURITY.decisionsTopic()).isEqualTo("decisions.security");
    }
}
