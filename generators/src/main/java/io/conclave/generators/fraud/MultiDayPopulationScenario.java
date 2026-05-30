package io.conclave.generators.fraud;

import io.conclave.events.fraud.PaymentEvent;
import io.conclave.generators.Distributions;
import io.conclave.generators.LabeledEvent;
import io.conclave.generators.Labels;
import io.conclave.generators.Scenario;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Multi-day, same-customer organic traffic for a whole population.
 *
 * <p>Generates {@code customers × days × eventsPerDay} CLEAN events. Each customer keeps a
 * stable {@link CustomerProfile} (identity, devices, merchants) across the whole window and
 * draws every transaction amount from their own {@link Distributions.Kind}. The result is a
 * realistic spread of per-customer behaviour over time — the negative-class history the M3
 * baseline learns from before the adversarial scenarios (ATO, bust-out, card-testing) try
 * to deviate from it.
 *
 * <p>Pass a single {@code distribution} to make the whole population homogeneous, or the
 * {@code mix} (null kind) to let every customer get a randomly assigned distribution — the
 * latter is what you want for thorough benchmarking across distribution shapes.
 */
public class MultiDayPopulationScenario implements Scenario {

    private final int customers;
    private final int days;
    private final int eventsPerDay;
    private final Distributions.Kind distribution; // null => per-customer mix
    private final Random random;
    private final Instant windowStart;

    public MultiDayPopulationScenario(int customers, int days, int eventsPerDay,
                                      Distributions.Kind distribution, Random random,
                                      Instant windowStart) {
        if (customers < 0 || days < 0 || eventsPerDay < 0) {
            throw new IllegalArgumentException("customers/days/eventsPerDay must be >= 0");
        }
        this.customers = customers;
        this.days = days;
        this.eventsPerDay = eventsPerDay;
        this.distribution = distribution;
        this.random = random;
        this.windowStart = windowStart;
    }

    @Override
    public Stream<LabeledEvent> generate() {
        List<LabeledEvent> events = new ArrayList<>(customers * days * eventsPerDay);
        for (int c = 0; c < customers; c++) {
            CustomerProfile profile = CustomerProfile.sample(c, distribution, random);
            for (int day = 0; day < days; day++) {
                for (int e = 0; e < eventsPerDay; e++) {
                    events.add(event(profile, day));
                }
            }
        }
        return events.stream();
    }

    private LabeledEvent event(CustomerProfile p, int day) {
        long amount = Distributions.amountMinor(p.amountKind, random, p.amountScaleMinor);
        // Spread the day's events across its 24h so timestamps look organic.
        Instant ts = windowStart
                .plus(Duration.ofDays(day))
                .plus(Duration.ofMinutes(random.nextInt(24 * 60)));
        String merchant = p.merchantPool.get(random.nextInt(p.merchantPool.size()));
        String device = p.devicePool.get(random.nextInt(p.devicePool.size()));
        PaymentEvent event = PaymentEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTimestamp(ts)
                .setCardholderId(p.cardholderId)
                .setCardToken(p.cardToken)
                .setAmountMinor(amount)
                .setCurrency(p.currency)
                .setMerchantId(merchant)
                .setMerchantCategoryCode(FraudCatalog.MCCS.get(random.nextInt(FraudCatalog.MCCS.size())))
                .setBin(p.bin)
                .setDeviceFingerprint(device)
                .setIpAddress(CleanFraudScenario.syntheticIp(p.homeCountry, random))
                .setBillingCountry(p.homeCountry)
                .setShippingCountry(p.homeCountry)
                .setCardPresent(false)
                .setChannel(p.preferredChannel)
                .build();
        return new LabeledEvent(event, Labels.CLEAN, "fraud-population-" + p.cardholderId,
                "multi-day organic, day=" + day + ", dist=" + p.amountKind + ", amount=" + amount);
    }
}
