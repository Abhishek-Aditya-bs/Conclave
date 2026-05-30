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
 * Account takeover: a cardholder with a long benign history suddenly transacts from
 * a far country on an unfamiliar device. The behavioral baseline embedding shifts;
 * the judge fires.
 *
 * <p>Emits a short benign warmup tail followed by the takeover transactions so an
 * eval comparing pre/post baseline can see the regime change.
 */
public class FraudAtoScenario implements Scenario {

    private final int campaignIndex;
    private final int benignTail;
    private final int takeoverCount;
    private final Random random;
    private final Instant baseTime;

    public FraudAtoScenario(int campaignIndex, int benignTail, int takeoverCount,
                            Random random, Instant baseTime) {
        this.campaignIndex = campaignIndex;
        this.benignTail = benignTail;
        this.takeoverCount = takeoverCount;
        this.random = random;
        this.baseTime = baseTime;
    }

    @Override
    public Stream<LabeledEvent> generate() {
        String cardholder = "ch_ato_" + campaignIndex;
        String homeCountry = FraudCatalog.HOME_COUNTRIES.get(0);
        String farCountry = FraudCatalog.FAR_COUNTRIES.get(campaignIndex % FraudCatalog.FAR_COUNTRIES.size());
        String scenarioId = "fraud-ato-" + campaignIndex;

        Stream<LabeledEvent> warmup = Stream.iterate(0, i -> i < benignTail, i -> i + 1)
                .map(i -> {
                    PaymentEvent ev = PaymentEvent.newBuilder()
                            .setEventId(UUID.randomUUID().toString())
                            .setTimestamp(baseTime.plus(Duration.ofMinutes(i)))
                            .setCardholderId(cardholder)
                            .setCardToken("tok_" + cardholder)
                            .setAmountMinor(500L + random.nextInt(4500))
                            .setCurrency("USD")
                            .setMerchantId("merch_" + (random.nextInt(50) + 1))
                            .setMerchantCategoryCode(5411)
                            .setBin(FraudCatalog.BINS.get(0))
                            .setDeviceFingerprint("dev_home_" + campaignIndex)
                            .setIpAddress(CleanFraudScenario.syntheticIp(homeCountry, random))
                            .setBillingCountry(homeCountry)
                            .setShippingCountry(homeCountry)
                            .setCardPresent(false)
                            .setChannel(Channel.WEB)
                            .build();
                    return new LabeledEvent(ev, Labels.CLEAN, scenarioId + "-warmup",
                            "benign warmup before ATO " + campaignIndex);
                });

        Stream<LabeledEvent> takeover = Stream.iterate(0, i -> i < takeoverCount, i -> i + 1)
                .map(i -> {
                    PaymentEvent ev = PaymentEvent.newBuilder()
                            .setEventId(UUID.randomUUID().toString())
                            .setTimestamp(baseTime.plus(Duration.ofMinutes(benignTail + i)))
                            .setCardholderId(cardholder)
                            .setCardToken("tok_" + cardholder)
                            .setAmountMinor(40_000L + random.nextInt(60_000))
                            .setCurrency("USD")
                            .setMerchantId("merch_jewelry_" + campaignIndex)
                            .setMerchantCategoryCode(5944)
                            .setBin(FraudCatalog.BINS.get(0))
                            .setDeviceFingerprint("dev_takeover_" + campaignIndex)
                            .setIpAddress(CleanFraudScenario.syntheticIp(farCountry, random))
                            .setBillingCountry(homeCountry)
                            .setShippingCountry(farCountry)
                            .setCardPresent(false)
                            .setChannel(Channel.WEB)
                            .build();
                    return new LabeledEvent(ev, Labels.FRAUD_ATO, scenarioId,
                            "ATO " + campaignIndex + " — geo flip " + homeCountry + "→" + farCountry);
                });

        return Stream.concat(warmup, takeover);
    }
}
