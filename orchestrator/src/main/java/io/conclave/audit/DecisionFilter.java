package io.conclave.audit;

import java.time.Instant;
import java.util.Optional;

/**
 * Composable query filter for the audit list endpoint.
 *
 * <p>Built from REST query parameters by {@link AuditController}. All
 * fields optional — missing means "no constraint". {@link #limit} and
 * {@link #offset} are required for stable pagination.
 *
 * <p>Defaults:
 * <ul>
 *   <li>{@code limit = 50} — small enough that the audit UI renders one
 *       page above the fold; capped at {@link #MAX_LIMIT} to keep
 *       pathological queries off the audit hot path.</li>
 *   <li>{@code offset = 0}.</li>
 *   <li>All filter predicates default to "no constraint".</li>
 * </ul>
 *
 * <p>Order is hardwired to {@code created_at DESC} so newer decisions
 * always lead. The {@code idx_decisions_created_at} index covers this.
 */
public record DecisionFilter(
        Optional<String> domain,
        Optional<String> verdictLabel,
        Optional<String> baselineEntityId,
        Optional<Double> minScore,
        Optional<Double> maxScore,
        Optional<Instant> since,
        Optional<Instant> until,
        Optional<String> judgeProvider,
        int limit,
        int offset
) {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;
    public static final int DEFAULT_OFFSET = 0;

    public DecisionFilter {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0; got " + limit);
        }
        if (limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be <= " + MAX_LIMIT + "; got " + limit);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0; got " + offset);
        }
        minScore.ifPresent(v -> {
            if (v < 0.0 || v > 1.0) {
                throw new IllegalArgumentException("minScore must be in [0, 1]; got " + v);
            }
        });
        maxScore.ifPresent(v -> {
            if (v < 0.0 || v > 1.0) {
                throw new IllegalArgumentException("maxScore must be in [0, 1]; got " + v);
            }
        });
        if (minScore.isPresent() && maxScore.isPresent() && minScore.get() > maxScore.get()) {
            throw new IllegalArgumentException(
                    "minScore (" + minScore.get() + ") must be <= maxScore (" + maxScore.get() + ")");
        }
    }

    /** Empty filter: all defaults, no predicates. */
    public static DecisionFilter empty() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder; the controller layer assembles a filter from query params. */
    public static final class Builder {
        private Optional<String> domain = Optional.empty();
        private Optional<String> verdictLabel = Optional.empty();
        private Optional<String> baselineEntityId = Optional.empty();
        private Optional<Double> minScore = Optional.empty();
        private Optional<Double> maxScore = Optional.empty();
        private Optional<Instant> since = Optional.empty();
        private Optional<Instant> until = Optional.empty();
        private Optional<String> judgeProvider = Optional.empty();
        private int limit = DEFAULT_LIMIT;
        private int offset = DEFAULT_OFFSET;

        public Builder domain(String d) { this.domain = Optional.ofNullable(blankToNull(d)); return this; }
        public Builder verdictLabel(String l) { this.verdictLabel = Optional.ofNullable(blankToNull(l)); return this; }
        public Builder baselineEntityId(String e) { this.baselineEntityId = Optional.ofNullable(blankToNull(e)); return this; }
        public Builder minScore(Double s) { this.minScore = Optional.ofNullable(s); return this; }
        public Builder maxScore(Double s) { this.maxScore = Optional.ofNullable(s); return this; }
        public Builder since(Instant t) { this.since = Optional.ofNullable(t); return this; }
        public Builder until(Instant t) { this.until = Optional.ofNullable(t); return this; }
        public Builder judgeProvider(String p) { this.judgeProvider = Optional.ofNullable(blankToNull(p)); return this; }
        public Builder limit(int l) { this.limit = l; return this; }
        public Builder offset(int o) { this.offset = o; return this; }

        public DecisionFilter build() {
            return new DecisionFilter(
                    domain, verdictLabel, baselineEntityId,
                    minScore, maxScore, since, until, judgeProvider,
                    limit, offset);
        }

        private static String blankToNull(String s) {
            return (s == null || s.isBlank()) ? null : s;
        }
    }
}
