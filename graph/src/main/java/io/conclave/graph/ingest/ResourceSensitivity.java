package io.conclave.graph.ingest;

import java.util.Locale;

/**
 * Classifies a target-resource path into a sensitivity tier that
 * {@code SecurityPrivilegedAccessTemplate} understands: {@code restricted},
 * {@code high}, or {@code low}. The template fires on {@code high}/{@code restricted}.
 *
 * <p>Deterministic keyword heuristic — good enough to tag the synthetic exfil
 * resources (keys, dumps, exports, finance/billing/identity files) without
 * coupling the graph service to any generator internals. A production deployment
 * would replace this with a data-classification lookup.
 */
public final class ResourceSensitivity {

    private ResourceSensitivity() {
        /* static-only */
    }

    // Highest tier: credentials, keys, raw dumps.
    private static final String[] RESTRICTED = {
        "key", "secret", "root", "credential", "token", "dump",
        ".pem", ".sql", "/vault", "ca.", "private"
    };

    // Sensitive business data: exports, financials, identities, admin surfaces.
    private static final String[] HIGH = {
        "export", "finance", "billing", "identit", "admin", "customer",
        "payroll", "/secrets", ".csv", ".xlsx", "password"
    };

    /** @return {@code "restricted"}, {@code "high"}, or {@code "low"}. */
    public static String classify(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return "low";
        }
        String p = resourcePath.toLowerCase(Locale.ROOT);
        for (String k : RESTRICTED) {
            if (p.contains(k)) {
                return "restricted";
            }
        }
        for (String k : HIGH) {
            if (p.contains(k)) {
                return "high";
            }
        }
        return "low";
    }
}
