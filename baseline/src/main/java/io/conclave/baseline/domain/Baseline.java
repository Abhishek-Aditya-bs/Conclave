package io.conclave.baseline.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * A per-entity rolling embedding for one domain configuration. The embedding is an
 * exponential-moving-average over the textualized event sequence for this entity — see
 * the {@code BaselineService} for the update rule and {@code docs/adr/0002-...} for the
 * design rationale.
 *
 * <p>Records normally derive {@code equals}/{@code hashCode} from accessor identity, but
 * {@code float[]} uses reference equality by default. We override both so callers can
 * compare baselines by content (and so {@code assertThat(baseline).isEqualTo(other)}
 * actually works in tests).
 */
public record Baseline(
        String entityId,
        String domain,
        float[] embedding,
        long eventCount,
        Instant lastUpdated) {

    public Baseline {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(embedding, "embedding");
        Objects.requireNonNull(lastUpdated, "lastUpdated");
        if (eventCount < 1) {
            throw new IllegalArgumentException("eventCount must be >= 1, got " + eventCount);
        }
    }

    public int dimensions() {
        return embedding.length;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Baseline that)) return false;
        return eventCount == that.eventCount
                && entityId.equals(that.entityId)
                && domain.equals(that.domain)
                && lastUpdated.equals(that.lastUpdated)
                && Arrays.equals(embedding, that.embedding);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityId, domain, eventCount, lastUpdated, Arrays.hashCode(embedding));
    }

    @Override
    public String toString() {
        return "Baseline[entityId=" + entityId
                + ", domain=" + domain
                + ", embedding=<" + embedding.length + " floats>"
                + ", eventCount=" + eventCount
                + ", lastUpdated=" + lastUpdated + "]";
    }
}
