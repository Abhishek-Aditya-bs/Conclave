package io.conclave.audit;

import java.util.List;

/**
 * Paginated response envelope. {@code items} is the page, {@code total}
 * is the unfiltered match count so the dashboard can render a "page N
 * of M" indicator. {@code limit} + {@code offset} echo back the request
 * for ease of client-side bookkeeping.
 */
public record DecisionPage(
        List<DecisionSummary> items,
        long total,
        int limit,
        int offset
) {
}
