package io.conclave.generators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.conclave.events.fraud.Channel;
import io.conclave.events.fraud.PaymentEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EventPublisherTest {

    @Test
    @DisplayName("publish routes event to raw topic and label JSON to labels topic, keyed by eventId")
    void routesToBothTopics() {
        @SuppressWarnings("unchecked")
        Producer<String, SpecificRecord> raw = mock(Producer.class);
        @SuppressWarnings("unchecked")
        Producer<String, String> labels = mock(Producer.class);
        RecordMetadata meta = new RecordMetadata(new TopicPartition("t", 0), 0, 0, 0, 0, 0);
        when(raw.send(any())).thenReturn(CompletableFuture.completedFuture(meta));
        when(labels.send(any())).thenReturn(CompletableFuture.completedFuture(meta));

        Clock fixed = Clock.fixed(Instant.ofEpochMilli(1700000000000L), ZoneOffset.UTC);
        EventPublisher publisher = new EventPublisher(
                GeneratorDomain.FRAUD, raw, labels, fixed);

        PaymentEvent event = paymentEvent("evt-42");
        publisher.publish(new LabeledEvent(event, Labels.CARD_TESTING_RING, "ring-1", "reason"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, SpecificRecord>> rawCap =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(raw).send(rawCap.capture());
        ProducerRecord<String, SpecificRecord> rawRec = rawCap.getValue();
        assertThat(rawRec.topic()).isEqualTo("events.fraud.raw");
        assertThat(rawRec.key()).isEqualTo("evt-42");
        assertThat(rawRec.value()).isSameAs(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> labelCap =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(labels).send(labelCap.capture());
        ProducerRecord<String, String> labelRec = labelCap.getValue();
        assertThat(labelRec.topic()).isEqualTo("events.fraud.labels");
        assertThat(labelRec.key()).isEqualTo("evt-42");
        assertThat(labelRec.value())
                .contains("\"event_id\":\"evt-42\"")
                .contains("\"label\":\"CARD_TESTING_RING\"")
                .contains("\"scenario_id\":\"ring-1\"")
                .contains("\"emitted_at_ms\":1700000000000");
    }

    @Test
    @DisplayName("close flushes and closes both producers")
    void closeFlushesAndCloses() {
        @SuppressWarnings("unchecked")
        Producer<String, SpecificRecord> raw = mock(Producer.class);
        @SuppressWarnings("unchecked")
        Producer<String, String> labels = mock(Producer.class);
        EventPublisher publisher = new EventPublisher(
                GeneratorDomain.SECURITY, raw, labels, Clock.systemUTC());
        publisher.close();
        verify(raw, times(1)).flush();
        verify(labels, times(1)).flush();
        verify(raw, times(1)).close();
        verify(labels, times(1)).close();
    }

    @Test
    @DisplayName("event without eventId field rejected")
    void missingEventIdFieldRejected() {
        // PaymentEvent always has eventId, so we synthesize a record missing it by
        // using extractEventId on a record whose eventId is null.
        PaymentEvent broken = paymentEvent("ignored");
        broken.put(0, null); // null out eventId
        assertThatThrownBy(() -> EventPublisher.extractEventId(broken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null eventId");
    }

    private static PaymentEvent paymentEvent(String eventId) {
        return PaymentEvent.newBuilder()
                .setEventId(eventId)
                .setTimestamp(Instant.ofEpochMilli(1700000000000L))
                .setCardholderId("ch_1")
                .setCardToken("tok_1")
                .setAmountMinor(100L)
                .setCurrency("USD")
                .setMerchantId("merch_1")
                .setMerchantCategoryCode(5411)
                .setBin("424242")
                .setDeviceFingerprint("dev_1")
                .setIpAddress("10.0.0.1")
                .setBillingCountry("US")
                .setShippingCountry(null)
                .setCardPresent(false)
                .setChannel(Channel.WEB)
                .build();
    }
}
