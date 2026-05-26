package io.conclave.generators.fraud;

import io.conclave.events.fraud.Channel;
import io.conclave.events.fraud.PaymentEvent;
import io.conclave.generators.LabeledEvent;
import io.conclave.generators.Labels;
import io.conclave.generators.Scenario;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Bust-out: a cardholder establishes a clean history with small repayments, then
 * abruptly maxes the line. M3's baseline catches the regime change after the line
 * exceeds the per-entity envelope.
 *
 * <p>The benign tail is intentionally longer than ATO's so the baseline embedding
 * has time to converge before the bust-out events arrive.
 */
public class BustOutScenario implements Scenario {

    private final int campaignIndex;
    private final int rampSteps;
    private final int bustEvents;
    private final Random random;
    private final Instant baseTime;

    public BustOutScenario(int campaignIndex, int rampSteps, int bustEvents,
                           Random random, Instant baseTime) {
        this.campaignIndex = campaignIndex;
        this.rampSteps = rampSteps;
        this.bustEvents = bustEvents;
        this.random = random;
        this.baseTime = baseTime;
    }

    @Override
    public Stream<LabeledEvent> generate() {
        String cardholder = "ch_bustout_" + campaignIndex;
        String scenarioId = "fraud-bustout-" + campaignIndex;
        Stream<LabeledEvent> ramp = Stream.iterate(0, i -> i < rampSteps, i -> i + 1)
                .map(i -> {
                    long amount = 200L + i * 150L + random.nextInt(50);
                    PaymentEvent ev = baseBuilder(cardholder)
                            .setEventId(UUID.randomUUID().toString())
                            .setTimestamp(baseTime.plus(Duration.ofHours(i)))
                            .setAmountMinor(amount)
                            .setMerchantId("merch_grocery_" + campaignIndex)
                            .setMerchantCategoryCode(5411)
                            .setBin(FraudCatalog.BINS.get(0))
                            .setDeviceFingerprint("dev_bust_" + campaignIndex)
                            .setIpAddress("10." + campaignIndex + ".0." + (1 + random.nextInt(254)))
                            .setBillingCountry("US")
                            .setShippingCountry("US")
                            .setChannel(Channel.WEB)
                            .build();
                    return new LabeledEvent(ev, Labels.CLEAN, scenarioId + "-ramp",
                            "bust-out ramp " + campaignIndex + " step " + i);
                });
        Stream<LabeledEvent> bust = Stream.iterate(0, i -> i < bustEvents, i -> i + 1)
                .map(i -> {
                    PaymentEvent ev = baseBuilder(cardholder)
                            .setEventId(UUID.randomUUID().toString())
                            .setTimestamp(baseTime.plus(Duration.ofHours(rampSteps)).plus(Duration.ofMinutes(i)))
                            .setAmountMinor(150_000L + random.nextInt(200_000))
                            .setMerchantId("merch_electronics_" + campaignIndex)
                            .setMerchantCategoryCode(5732)
                            .setBin(FraudCatalog.BINS.get(0))
                            .setDeviceFingerprint("dev_bust_" + campaignIndex)
                            .setIpAddress("10." + campaignIndex + ".0." + (1 + random.nextInt(254)))
                            .setBillingCountry("US")
                            .setShippingCountry("US")
                            .setChannel(Channel.WEB)
                            .build();
                    return new LabeledEvent(ev, Labels.BUST_OUT, scenarioId,
                            "bust-out " + campaignIndex + " — high-ticket #" + i);
                });
        return Stream.concat(ramp, bust);
    }

    private static PaymentEvent.Builder baseBuilder(String cardholder) {
        return PaymentEvent.newBuilder()
                .setCardholderId(cardholder)
                .setCardToken("tok_" + cardholder)
                .setCurrency("USD")
                .setCardPresent(false);
    }
}
