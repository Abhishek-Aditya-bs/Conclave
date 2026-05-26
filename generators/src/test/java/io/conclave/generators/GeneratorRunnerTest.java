package io.conclave.generators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.conclave.events.fraud.Channel;
import io.conclave.events.fraud.PaymentEvent;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

class GeneratorRunnerTest {

    @Test
    @DisplayName("runs every scenario, counts clean vs adversarial, returns summary")
    void countsLabelsCorrectly() {
        EventPublisher publisher = mock(EventPublisher.class);
        doNothing().when(publisher).publish(Mockito.any());

        Scenario s1 = () -> Stream.of(
                labeled("e1", Labels.CLEAN),
                labeled("e2", Labels.CLEAN),
                labeled("e3", Labels.CARD_TESTING_RING));
        Scenario s2 = () -> Stream.of(
                labeled("e4", Labels.FRAUD_ATO));

        GeneratorRunner.RunSummary summary = new GeneratorRunner(publisher).run(List.of(s1, s2));
        assertThat(summary.total()).isEqualTo(4);
        assertThat(summary.clean()).isEqualTo(2);
        assertThat(summary.adversarial()).isEqualTo(2);
        verify(publisher, times(4)).publish(Mockito.any());
    }

    @Test
    @DisplayName("scenarios are drained in declaration order")
    void scenariosInOrder() {
        EventPublisher publisher = mock(EventPublisher.class);
        Scenario s1 = () -> Stream.of(labeled("a", Labels.CLEAN));
        Scenario s2 = () -> Stream.of(labeled("b", Labels.CLEAN));
        new GeneratorRunner(publisher).run(List.of(s1, s2));
        InOrder order = Mockito.inOrder(publisher);
        order.verify(publisher).publish(Mockito.argThat(le -> "a".equals(le.event().get(0))));
        order.verify(publisher).publish(Mockito.argThat(le -> "b".equals(le.event().get(0))));
    }

    @Test
    @DisplayName("publish exception aborts the run (no partially-true label stream)")
    void abortsOnFailure() {
        EventPublisher publisher = mock(EventPublisher.class);
        doThrow(new EventPublisher.PublishException("simulated", new RuntimeException()))
                .when(publisher).publish(Mockito.any());
        Scenario s1 = () -> Stream.of(labeled("x", Labels.CLEAN));
        try {
            new GeneratorRunner(publisher).run(List.of(s1));
            throw new AssertionError("expected PublishException");
        } catch (EventPublisher.PublishException expected) {
            assertThat(expected).hasMessageContaining("simulated");
        }
    }

    private static LabeledEvent labeled(String eventId, Labels label) {
        PaymentEvent event = PaymentEvent.newBuilder()
                .setEventId(eventId)
                .setTimestamp(Instant.ofEpochMilli(0))
                .setCardholderId("c")
                .setCardToken("t")
                .setAmountMinor(1L)
                .setCurrency("USD")
                .setMerchantId("m")
                .setMerchantCategoryCode(0)
                .setBin("000000")
                .setDeviceFingerprint("d")
                .setIpAddress("0.0.0.0")
                .setBillingCountry("US")
                .setShippingCountry(null)
                .setCardPresent(false)
                .setChannel(Channel.WEB)
                .build();
        return new LabeledEvent(event, label, "s", "r");
    }
}
