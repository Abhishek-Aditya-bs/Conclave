package io.conclave.generators.fraud;

import io.conclave.events.fraud.Channel;
import io.conclave.generators.Distributions;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A stable synthetic cardholder identity reused across many days of events. Each profile
 * fixes the things that should stay constant for a real customer — home country, currency,
 * card token, a small device pool, a handful of habitual merchants, a preferred channel —
 * plus the amount <em>distribution</em> their spend is drawn from. Reusing the same
 * profile across days is what lets the M3 baseline converge to a per-customer steady state
 * that a later anomalous event can then deviate from.
 */
final class CustomerProfile {

    final String cardholderId;
    final String cardToken;
    final String homeCountry;
    final String currency;
    final String bin;
    final List<String> devicePool;
    final List<String> merchantPool;
    final Channel preferredChannel;
    final Distributions.Kind amountKind;
    /** Typical spend in minor units (cents); the distribution is centred here. */
    final double amountScaleMinor;

    private CustomerProfile(String cardholderId, String cardToken, String homeCountry,
                            String currency, String bin, List<String> devicePool,
                            List<String> merchantPool, Channel preferredChannel,
                            Distributions.Kind amountKind, double amountScaleMinor) {
        this.cardholderId = cardholderId;
        this.cardToken = cardToken;
        this.homeCountry = homeCountry;
        this.currency = currency;
        this.bin = bin;
        this.devicePool = devicePool;
        this.merchantPool = merchantPool;
        this.preferredChannel = preferredChannel;
        this.amountKind = amountKind;
        this.amountScaleMinor = amountScaleMinor;
    }

    /**
     * Build a deterministic profile for customer {@code index}. If {@code forcedKind} is
     * null, the customer's distribution is chosen at random (the {@code mix} mode).
     */
    static CustomerProfile sample(int index, Distributions.Kind forcedKind, Random r) {
        int ci = r.nextInt(FraudCatalog.HOME_COUNTRIES.size());
        String country = FraudCatalog.HOME_COUNTRIES.get(ci);
        String currency = FraudCatalog.CURRENCIES.get(Math.min(ci, FraudCatalog.CURRENCIES.size() - 1));
        String cardholder = "ch_pop_" + index;

        // 1-2 habitual devices.
        List<String> devices = new ArrayList<>();
        int deviceCount = 1 + r.nextInt(2);
        for (int d = 0; d < deviceCount; d++) {
            devices.add("dev_pop_" + index + "_" + d);
        }

        // 3-6 habitual merchants.
        List<String> merchants = new ArrayList<>();
        int merchantCount = 3 + r.nextInt(4);
        for (int m = 0; m < merchantCount; m++) {
            merchants.add("merch_" + (1 + r.nextInt(500)));
        }

        Channel channel = Channel.values()[r.nextInt(Channel.values().length)];
        Distributions.Kind kind = forcedKind != null ? forcedKind : Distributions.randomKind(r);
        // Typical spend between ~$15 and ~$215 (in minor units).
        double scaleMinor = 1_500 + r.nextInt(20_000);

        return new CustomerProfile(
                cardholder,
                "tok_" + (cardholder.hashCode() & 0xFFFF),
                country,
                currency,
                FraudCatalog.BINS.get(r.nextInt(FraudCatalog.BINS.size())),
                devices,
                merchants,
                channel,
                kind,
                scaleMinor);
    }
}
