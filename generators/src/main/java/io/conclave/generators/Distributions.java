package io.conclave.generators;

import java.util.Random;

/**
 * Amount-distribution samplers for synthetic traffic. Different customers draw their
 * spend from different distributions so the population spans a realistic range of
 * behaviours — which is exactly what makes the M3 cosine-similarity baseline (and the
 * deviation signal that flags ATO / bust-out) meaningful during benchmarking.
 *
 * <p>All samplers return an amount in <em>minor units</em> (cents), clamped to a sane
 * range so a heavy tail can't emit absurd values.
 */
public final class Distributions {

    /** Floor: $1.00. */
    private static final long MIN_MINOR = 100L;
    /** Ceiling: $50,000.00 — keeps the Pareto tail bounded. */
    private static final long MAX_MINOR = 5_000_000L;

    private Distributions() {
        /* static-only */
    }

    /** The supported per-customer spend distributions. */
    public enum Kind {
        /** Tight, symmetric spend around a mean (e.g. a salaried commuter). */
        GAUSSIAN,
        /** Right-skewed — many small charges, a few large (typical consumer). */
        LOGNORMAL,
        /** Heavy-tailed — rare but very large purchases (whales / B2B). */
        PARETO,
        /** Flat spread across a band (e.g. fixed-tier subscriptions sampled evenly). */
        UNIFORM,
        /** Two clusters — everyday small spend plus a recurring large bill. */
        BIMODAL
    }

    /** Parse a CLI distribution token; {@code "mix"} returns null (= per-customer random). */
    public static Kind parseOrMix(String token) {
        if (token == null || token.isBlank() || token.equalsIgnoreCase("mix")) {
            return null;
        }
        return Kind.valueOf(token.trim().toUpperCase());
    }

    /** Pick a random distribution kind — used when the CLI asks for the {@code mix}. */
    public static Kind randomKind(Random r) {
        Kind[] all = Kind.values();
        return all[r.nextInt(all.length)];
    }

    /**
     * Sample one amount in minor units from {@code kind}, centred on {@code scaleMinor}
     * (the customer's typical spend, also in minor units).
     */
    public static long amountMinor(Kind kind, Random r, double scaleMinor) {
        double v = switch (kind) {
            case GAUSSIAN -> scaleMinor + r.nextGaussian() * scaleMinor * 0.30;
            case LOGNORMAL -> Math.exp(Math.log(Math.max(1.0, scaleMinor)) + r.nextGaussian() * 0.6);
            case PARETO -> {
                double alpha = 1.5;                  // shape: <2 => heavy tail
                double xm = scaleMinor * 0.5;        // scale (minimum of the support)
                double u = Math.max(1e-9, r.nextDouble());
                yield xm / Math.pow(u, 1.0 / alpha);
            }
            case UNIFORM -> scaleMinor * 0.2 + r.nextDouble() * scaleMinor * 1.8;
            case BIMODAL -> {
                double centre = r.nextBoolean() ? scaleMinor * 2.5 : scaleMinor * 0.4;
                yield centre + r.nextGaussian() * centre * 0.2;
            }
        };
        long minor = Math.round(v);
        return Math.max(MIN_MINOR, Math.min(minor, MAX_MINOR));
    }
}
