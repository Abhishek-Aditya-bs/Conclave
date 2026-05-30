package io.conclave.baseline.ingest;

import java.util.function.Function;

/**
 * Turns an enriched event into the behavioral text string the embedding model sees.
 *
 * <p>One textualizer, two callers (so the embedding the baseline is BUILT from and the
 * one it is SCORED against are always produced identically):
 * <ul>
 *   <li>the Kafka consumer feeds it an accessor over a generic Avro record;</li>
 *   <li>the ScoreEvent RPC (Stage 1c) feeds it an accessor over parsed JSON.</li>
 * </ul>
 *
 * <p>Amounts are bucketed so ordinary spend variation doesn't churn the text (keeping
 * normal embeddings clustered) while a regime change — a far country, a jewelry MCC, an
 * {@code xlarge} amount, a scripted user-agent — visibly shifts it. That shift is what
 * makes the cosine-similarity deviation signal (ATO / bust-out) work.
 */
public final class BaselineText {

    private BaselineText() {
        /* static-only */
    }

    /** Build the text for the given domain from a field accessor. */
    public static String of(String domain, Function<String, Object> fields) {
        return switch (domain) {
            case "fraud" -> fraud(fields);
            case "security" -> security(fields);
            default -> generic(fields);
        };
    }

    static String fraud(Function<String, Object> f) {
        long amount = lng(f, "amountMinor");
        String currency = orUnknown(str(f, "currency"));
        long mcc = lng(f, "merchantCategoryCode");
        String billing = orUnknown(str(f, "billingCountry"));
        String shipping = str(f, "shippingCountry");
        String channel = orUnknown(str(f, "channel"));
        boolean cardPresent = bool(f, "cardPresent");
        return String.join(" ",
                "payment",
                "amount=" + amountBucket(amount),
                "currency=" + currency,
                "mcc=" + mcc,
                "billing=" + billing,
                "shipping=" + (shipping == null || shipping.isBlank() ? billing : shipping),
                "channel=" + channel,
                cardPresent ? "card_present" : "card_not_present");
    }

    static String security(Function<String, Object> f) {
        String method = orUnknown(str(f, "authMethod"));
        String result = orUnknown(str(f, "result"));
        String host = orUnknown(str(f, "hostId"));
        boolean privileged = bool(f, "isPrivileged");
        String resource = str(f, "targetResource");
        String userAgent = str(f, "userAgent");
        return String.join(" ",
                "auth",
                "method=" + method,
                "result=" + result,
                "host=" + host,
                privileged ? "privileged" : "standard",
                (resource == null || resource.isBlank()) ? "no_resource" : "resource=" + resource,
                (userAgent == null || userAgent.isBlank()) ? "agent=unknown" : "agent=" + userAgent);
    }

    private static String generic(Function<String, Object> f) {
        Object id = f.apply("eventId");
        return "event id=" + (id == null ? "?" : id);
    }

    /** Coarse amount tier (amount in minor units, e.g. cents). */
    static String amountBucket(long amountMinor) {
        if (amountMinor < 2_000) {
            return "small";       // < $20
        }
        if (amountMinor < 10_000) {
            return "medium";      // < $100
        }
        if (amountMinor < 50_000) {
            return "large";       // < $500
        }
        return "xlarge";          // >= $500
    }

    // ---- coercion helpers (tolerate Avro Utf8/Long and JSON Integer/Double) ----

    private static String str(Function<String, Object> f, String key) {
        Object v = f.apply(key);
        return v == null ? null : v.toString();
    }

    private static long lng(Function<String, Object> f, String key) {
        Object v = f.apply(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static boolean bool(Function<String, Object> f, String key) {
        Object v = f.apply(key);
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }

    private static String orUnknown(String s) {
        return (s == null || s.isBlank()) ? "unknown" : s;
    }
}
