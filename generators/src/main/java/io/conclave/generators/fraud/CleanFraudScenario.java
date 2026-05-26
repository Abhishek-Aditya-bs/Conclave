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
 * Indistinguishable organic CNP traffic: many distinct cardholders, normal merchants,
 * sensible amounts in the cardholder's home currency, devices rotated within a small
 * but plausible per-cardholder pool. Provides the negative-class volume the labeled
 * eval needs to compute precision@FPR=1%.
 */
public class CleanFraudScenario implements Scenario {

    private final int count;
    private final Random random;
    private final Instant baseTime;

    public CleanFraudScenario(int count, Random random, Instant baseTime) {
        this.count = count;
        this.random = random;
        this.baseTime = baseTime;
    }

    @Override
    public Stream<LabeledEvent> generate() {
        return Stream.generate(this::nextEvent).limit(count);
    }

    private LabeledEvent nextEvent() {
        String cardholder = "ch_" + (1000 + random.nextInt(5000));
        int countryIdx = random.nextInt(FraudCatalog.HOME_COUNTRIES.size());
        String country = FraudCatalog.HOME_COUNTRIES.get(countryIdx);
        String currency = FraudCatalog.CURRENCIES.get(Math.min(countryIdx, FraudCatalog.CURRENCIES.size() - 1));
        // 75% of organic spend is < $200 in minor units, occasional larger basket
        long amount = random.nextInt(100) < 75 ? 100L + random.nextInt(19_900) : 20_000L + random.nextInt(80_000);
        PaymentEvent event = PaymentEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTimestamp(baseTime.plus(Duration.ofMillis((long) random.nextInt(60_000))))
                .setCardholderId(cardholder)
                .setCardToken("tok_" + (cardholder.hashCode() & 0xFFFF))
                .setAmountMinor(amount)
                .setCurrency(currency)
                .setMerchantId("merch_" + (random.nextInt(500) + 1))
                .setMerchantCategoryCode(FraudCatalog.MCCS.get(random.nextInt(FraudCatalog.MCCS.size())))
                .setBin(FraudCatalog.BINS.get(random.nextInt(FraudCatalog.BINS.size())))
                .setDeviceFingerprint("dev_" + (cardholder.hashCode() & 0xFF))
                .setIpAddress(syntheticIp(country, random))
                .setBillingCountry(country)
                .setShippingCountry(country)
                .setCardPresent(false)
                .setChannel(Channel.values()[random.nextInt(Channel.values().length)])
                .build();
        return new LabeledEvent(event, Labels.CLEAN, "clean-organic",
                "organic CNP, country=" + country + ", cardholder=" + cardholder);
    }

    static String syntheticIp(String country, Random random) {
        int seed = country.hashCode() & 0x7F;
        return seed + "." + random.nextInt(256) + "." + random.nextInt(256) + "." + (1 + random.nextInt(254));
    }
}
