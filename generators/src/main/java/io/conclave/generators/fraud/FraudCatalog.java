package io.conclave.generators.fraud;

import java.util.List;

/**
 * Static reference data for synthetic fraud traffic. Realistic enough that
 * BIN/MCC/country fields read like organic Stripe-style payments while staying
 * fully synthetic.
 */
final class FraudCatalog {

    /** Common card BINs across major issuers. */
    static final List<String> BINS = List.of(
            "411111", "401200", "424242", "555555", "542418", "601111", "378282", "371449",
            "352800", "543200", "510510", "455638");

    static final List<String> CURRENCIES = List.of("USD", "EUR", "GBP", "INR", "JPY");

    /** MCCs spanning grocery, e-commerce, travel, digital goods. */
    static final List<Integer> MCCS = List.of(
            5411, 5812, 5942, 5734, 7995, 5732, 4511, 4111, 5999, 5311, 5969, 5462);

    /** Countries weighted by sample distribution; first is "home" baseline. */
    static final List<String> HOME_COUNTRIES = List.of("US", "GB", "DE", "IN", "JP", "FR");

    /** Countries used to signal abnormal geo for ATO scenarios. */
    static final List<String> FAR_COUNTRIES = List.of("NG", "RU", "VN", "BR", "PK");

    /** Channels weighted toward CNP traffic (WEB + MOBILE + API). */
    static final List<String> CHANNELS = List.of("WEB", "MOBILE", "API", "OTHER");

    private FraudCatalog() {}
}
