package io.conclave.baseline.ingest;

import java.util.function.Function;

/**
 * Turns an enriched event into the behavioral text string the embedding model sees.
 *
 * <p>One textualizer, two callers (so the embedding the baseline is BUILT from and the
 * one it is SCORED against are always produced identically):
 * <ul>
 *   <li>the Kafka consumer feeds it an accessor over a generic Avro record;</li>
 *   <li>the ScoreEvent RPC feeds it an accessor over parsed JSON.</li>
 * </ul>
 *
 * <p>Amounts are bucketed so ordinary spend variation doesn't churn the text (keeping
 * normal embeddings clustered) while a regime change — a far country, a jewelry MCC, an
 * {@code xlarge} amount, a scripted user-agent — visibly shifts it. That shift is what
 * makes the cosine-similarity deviation signal (ATO / bust-out) work.
 *
 * <p>The text is short, so a single changed field (e.g. {@code result=FAILURE} among
 * {@code SUCCESS}es) would barely move the embedding. To make a genuinely
 * out-of-character event embed FAR from the entity's normal profile, the most
 * discriminating tokens — the result, a coarse user-agent class (automation vs
 * browser), the privilege flag, the amount magnitude bucket, and a high-risk-merchant
 * flag — are EMPHASIZED (repeated) so they dominate the bag-of-tokens similarity. The
 * encoding stays pure and deterministic: identical events always textualize identically.
 */
public final class BaselineText {

    /** Tokens repeated this many times to amplify their weight in the embedding. */
    private static final int EMPHASIS = 4;

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
        String resolvedShipping =
                (shipping == null || shipping.isBlank()) ? billing : shipping;
        boolean geoSplit = !resolvedShipping.equalsIgnoreCase(billing);

        StringBuilder sb = new StringBuilder();
        sb.append(String.join(" ",
                "payment",
                "amount=" + amountBucket(amount),
                "currency=" + currency,
                "mcc=" + mcc,
                "billing=" + billing,
                "shipping=" + resolvedShipping,
                "channel=" + channel,
                cardPresent ? "card_present" : "card_not_present"));

        // Emphasized discriminating tokens: amount magnitude and merchant risk are the
        // two fields that separate a bust-out / high-risk charge from routine spend.
        emit(sb, "amount_tier_" + amountBucket(amount));
        emit(sb, highRiskMcc(mcc) ? "merchant_high_risk" : "merchant_normal");
        if (geoSplit) {
            emit(sb, "geo_split");
        }
        return sb.toString();
    }

    static String security(Function<String, Object> f) {
        String method = orUnknown(str(f, "authMethod"));
        String result = orUnknown(str(f, "result"));
        String host = orUnknown(str(f, "hostId"));
        boolean privileged = bool(f, "isPrivileged");
        String resource = str(f, "targetResource");
        String userAgent = str(f, "userAgent");

        StringBuilder sb = new StringBuilder();
        sb.append(String.join(" ",
                "auth",
                "method=" + method,
                "result=" + result,
                "host=" + host,
                privileged ? "privileged" : "standard",
                (resource == null || resource.isBlank())
                        ? "no_resource" : "resource=" + resource,
                (userAgent == null || userAgent.isBlank())
                        ? "agent=unknown" : "agent=" + userAgent));

        // Emphasized discriminating tokens: the result (SUCCESS vs FAILURE), a coarse
        // user-agent class (automation vs browser), and the privilege flag are what
        // separate an auth attack from a routine login. Repeating them makes a single
        // FAILURE / scripted-agent / privileged event move the embedding far from the
        // entity's SUCCESS/browser/standard normal profile.
        emit(sb, "result_class_" + result.toUpperCase());
        emit(sb, "agent_class_" + userAgentClass(userAgent));
        emit(sb, privileged ? "privilege_yes" : "privilege_no");
        return sb.toString();
    }

    private static String generic(Function<String, Object> f) {
        Object id = f.apply("eventId");
        return "event id=" + (id == null ? "?" : id);
    }

    /** Appends a token repeated {@link #EMPHASIS} times to weight it in the embedding. */
    private static void emit(StringBuilder sb, String token) {
        for (int i = 0; i < EMPHASIS; i++) {
            sb.append(' ').append(token);
        }
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

    /**
     * Coarse user-agent classification (mirrors the orchestrator rule view). Scripted /
     * automation agents (curl, wget, python, boto3, go-http, java, bots, scanners) read
     * very differently from a browser.
     */
    static String userAgentClass(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "unknown";
        }
        String low = userAgent.toLowerCase();
        for (String marker : new String[] {
                "curl", "wget", "python", "boto", "go-http", "java", "bot", "scan"}) {
            if (low.contains(marker)) {
                return "automation";
            }
        }
        for (String marker : new String[] {
                "mozilla", "chrome", "safari", "firefox", "edge"}) {
            if (low.contains(marker)) {
                return "browser";
            }
        }
        return "other";
    }

    /** High-risk merchant category codes (jewelry, money transfer, gambling, etc.). */
    static boolean highRiskMcc(long mcc) {
        return mcc == 5944   // jewelry / precious metals
                || mcc == 5933   // pawn shops
                || mcc == 6051   // quasi-cash / crypto
                || mcc == 4829   // money transfer
                || mcc == 7995;  // gambling
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
