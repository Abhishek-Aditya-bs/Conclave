package io.conclave.graph.template;

import io.conclave.graph.domain.GraphFinding;
import java.util.List;
import java.util.Map;

/**
 * Small helpers shared by the concrete template implementations. Kept here (not on
 * an abstract base class) so individual templates remain structurally independent and
 * easy to add/remove. Public to allow templates in sub-packages (e.g.
 * {@code io.conclave.graph.template.fraud.*}) to use it.
 */
public final class Templates {

    private Templates() {
        /* static-only */
    }

    /** Read a required string param or fail fast with a clear message. */
    public static String required(Map<String, String> params, String key) {
        String v = params.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(
                    "Template parameter '" + key + "' is required (and must be non-blank)");
        }
        return v;
    }

    /** Standard "no-data" finding for a template when the query returns no rows. */
    public static GraphFinding empty(String templateName,
                                     String rootEntityId,
                                     String domain,
                                     Map<String, Object> emptyAttributes) {
        return new GraphFinding(templateName, rootEntityId, domain, emptyAttributes, 0.0, 0L);
    }

    public static List<String> emptyList() {
        return List.of();
    }
}
