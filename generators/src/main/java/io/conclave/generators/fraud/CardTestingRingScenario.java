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
 * Classic card-testing attack: a single attacker-controlled device fingerprint
 * runs many small ($1–$5) authorizations across freshly stolen cards to verify
 * which numbers are still live. The graph reasoner's
 * {@code FraudCardTestingRingTemplate} is exactly the detector for this.
 *
 * <p>One ring emits {@link #cardCount} cards × {@link #attemptsPerCard} attempts
 * each, all sharing the same device + IP + (usually) merchant. Different rings
 * use different device IDs.
 */
public class CardTestingRingScenario implements Scenario {

    private final int ringIndex;
    private final int cardCount;
    private final int attemptsPerCard;
    private final Random random;
    private final Instant baseTime;

    public CardTestingRingScenario(int ringIndex, int cardCount, int attemptsPerCard,
                                   Random random, Instant baseTime) {
        this.ringIndex = ringIndex;
        this.cardCount = cardCount;
        this.attemptsPerCard = attemptsPerCard;
        this.random = random;
        this.baseTime = baseTime;
    }

    @Override
    public Stream<LabeledEvent> generate() {
        String device = "dev_ring_" + ringIndex;
        String ip = "203.0.113." + (10 + ringIndex);
        String merchant = "merch_" + (900 + ringIndex);
        String bin = FraudCatalog.BINS.get(ringIndex % FraudCatalog.BINS.size());
        String scenarioId = "card-testing-ring-" + ringIndex;
        return Stream.iterate(0, i -> i < cardCount * attemptsPerCard, i -> i + 1)
                .map(i -> {
                    int cardIdx = i / attemptsPerCard;
                    int attemptIdx = i % attemptsPerCard;
                    String cardholder = "ch_ring" + ringIndex + "_card" + cardIdx;
                    PaymentEvent event = PaymentEvent.newBuilder()
                            .setEventId(UUID.randomUUID().toString())
                            .setTimestamp(baseTime.plus(Duration.ofSeconds(i * 2L + random.nextInt(2))))
                            .setCardholderId(cardholder)
                            .setCardToken("tok_stolen_" + ringIndex + "_" + cardIdx)
                            .setAmountMinor(100L + random.nextInt(400))
                            .setCurrency("USD")
                            .setMerchantId(merchant)
                            .setMerchantCategoryCode(5734)
                            .setBin(bin)
                            .setDeviceFingerprint(device)
                            .setIpAddress(ip)
                            .setBillingCountry("US")
                            .setShippingCountry(null)
                            .setCardPresent(false)
                            .setChannel(Channel.API)
                            .build();
                    return new LabeledEvent(event, Labels.CARD_TESTING_RING, scenarioId,
                            "card-testing ring " + ringIndex + ", card #" + cardIdx
                                    + " attempt " + attemptIdx + " (device=" + device + ")");
                });
    }
}
