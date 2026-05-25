package io.conclave.baseline.service;

import io.conclave.baseline.config.BaselineProperties;
import io.conclave.baseline.domain.Baseline;
import io.conclave.baseline.embedding.EmbeddingService;
import io.conclave.baseline.storage.BaselineRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The behavioral-baseline service that both the REST controller and the gRPC service
 * sit on top of. Two operations:
 *
 * <ul>
 *   <li>{@link #get} — fetch the current rolling embedding, or empty if unseen.</li>
 *   <li>{@link #update} — embed the new event text, fold it into the existing baseline
 *       via exponential moving average ({@code new = decay * old + (1 - decay) * fresh}),
 *       and persist.</li>
 * </ul>
 *
 * <p>An EMA was chosen over the "store last 90 days of events and recompute" approach
 * because the latter is O(events) per update and triples storage. EMA is O(1) per
 * update and matches a 90-day rolling window when the decay factor is tuned to the
 * expected event rate — see [docs/adr/0002-baseline-storage-and-embedding.md].
 */
@Service
public class BaselineService {

    private static final Logger LOG = LoggerFactory.getLogger(BaselineService.class);

    private final BaselineRepository repository;
    private final EmbeddingService embedder;
    private final double decay;
    private final Clock clock;

    @Autowired
    public BaselineService(BaselineRepository repository,
                           EmbeddingService embedder,
                           BaselineProperties properties) {
        this(repository, embedder, properties, Clock.systemUTC());
    }

    /** Visible-for-tests constructor that lets the test pin {@link Instant#now()}. */
    BaselineService(BaselineRepository repository,
                    EmbeddingService embedder,
                    BaselineProperties properties,
                    Clock clock) {
        this.repository = repository;
        this.embedder = embedder;
        this.decay = properties.emaDecay();
        this.clock = clock;
        if (embedder.dimensions() != properties.embeddingDim()) {
            throw new IllegalStateException(
                    "EmbeddingService dimensions (" + embedder.dimensions()
                  + ") do not match conclave.baseline.embedding-dim ("
                  + properties.embeddingDim() + "). Check application.yaml.");
        }
    }

    public Optional<Baseline> get(String domain, String entityId) {
        return repository.find(domain, entityId);
    }

    public Baseline update(String domain, String entityId, String eventText) {
        float[] fresh = embedder.embed(eventText);
        Optional<Baseline> existing = repository.find(domain, entityId);
        Baseline updated = existing
                .map(prev -> applyEma(prev, fresh))
                .orElseGet(() -> new Baseline(entityId, domain, fresh, 1L, clock.instant()));
        repository.save(updated);
        LOG.debug("baseline updated: domain={} entity={} eventCount={}",
                domain, entityId, updated.eventCount());
        return updated;
    }

    private Baseline applyEma(Baseline prev, float[] fresh) {
        float[] merged = new float[fresh.length];
        float[] prior  = prev.embedding();
        for (int i = 0; i < fresh.length; i++) {
            merged[i] = (float) (decay * prior[i] + (1.0 - decay) * fresh[i]);
        }
        return new Baseline(
                prev.entityId(),
                prev.domain(),
                merged,
                prev.eventCount() + 1,
                clock.instant());
    }
}
