package io.conclave.baseline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conclave.baseline.config.BaselineProperties;
import io.conclave.baseline.domain.Baseline;
import io.conclave.baseline.embedding.EmbeddingService;
import io.conclave.baseline.ingest.BaselineText;
import io.conclave.baseline.storage.BaselineRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
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
 *       via a count-aware exponential moving average, and persist.</li>
 * </ul>
 *
 * <p>An EMA was chosen over the "store last 90 days of events and recompute" approach
 * because the latter is O(events) per update and triples storage. EMA is O(1) per
 * update and matches a 90-day rolling window when the decay factor is tuned to the
 * expected event rate — see [docs/adr/0002-baseline-storage-and-embedding.md].
 *
 * <p><b>Cold-start bias &amp; the warmup.</b> A plain EMA ({@code new = decay*old +
 * (1-decay)*fresh}) with a high decay would pin a new entity's profile near its very
 * first event for many updates (the 2nd event of a decay=0.85 profile gets only 15%
 * weight). To avoid that, the effective decay is ramped: {@code effDecay = min(decay,
 * 1 - 1/n)} where {@code n} is the event count. For the first {@code ~1/(1-decay)}
 * events this is an unbiased running mean; once enough events have accrued it saturates
 * to the configured decay and behaves as a true EMA from there on. This is the standard
 * fix for EMA cold-start bias and makes early baselines trustworthy for scoring.
 */
@Service
public class BaselineService {

    private static final Logger LOG = LoggerFactory.getLogger(BaselineService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BaselineRepository repository;
    private final EmbeddingService embedder;
    private final double decay;
    private final double coldStartScore;
    private final double scoreMaxDistance;
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
        this.coldStartScore = properties.coldStartScore();
        this.scoreMaxDistance = properties.scoreMaxDistance();
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

    /**
     * Score an event against the entity's existing baseline WITHOUT mutating it.
     *
     * <p>Textualizes the enriched event the same way {@link #update} does (via
     * {@link BaselineText}), embeds it, and compares it to the stored baseline using
     * pgvector cosine similarity. The anomaly score is the cosine distance scaled so it
     * saturates to 1.0 at {@code score-max-distance}. Cold-start entities (no baseline
     * yet) get a neutral prior — there is nothing to compare against.
     *
     * <p>This is deliberately read-only: the event being judged is never folded back into
     * the baseline, so a fraudulent event can't poison the profile it is scored against.
     */
    public ScoreResult score(String domain, String entityId, String enrichedEventJson) {
        String text = BaselineText.of(domain, jsonAccessor(enrichedEventJson));
        float[] fresh = embedder.embed(text);
        Optional<BaselineRepository.ScoreLookup> lookup =
                repository.scoreLookup(domain, entityId, fresh);
        if (lookup.isEmpty()) {
            return new ScoreResult(coldStartScore, 0.0, true, 0L);
        }
        double cosine = lookup.get().cosineSimilarity();
        double distance = Math.max(0.0, 1.0 - cosine);
        double anomaly = Math.min(1.0, distance / scoreMaxDistance);
        return new ScoreResult(anomaly, cosine, false, lookup.get().eventCount());
    }

    /** Parse the enriched-event JSON into a field accessor for {@link BaselineText}. */
    private static Function<String, Object> jsonAccessor(String json) {
        if (json == null || json.isBlank()) {
            return key -> null;
        }
        try {
            Map<String, Object> map = MAPPER.readValue(json, new TypeReference<>() {});
            return map::get;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "enriched_event_json is not valid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Outcome of {@link #score}: a behavioral anomaly score in {@code [0, 1]}, the raw
     * cosine similarity to the baseline ({@code [-1, 1]}; 0 when cold-start), whether the
     * entity was cold-start, and how many events the baseline was built from.
     */
    public record ScoreResult(double anomalyScore, double cosineSimilarity,
                              boolean coldStart, long eventCount) {}

    private Baseline applyEma(Baseline prev, float[] fresh) {
        long newCount = prev.eventCount() + 1;
        // Count-aware warmup (see class Javadoc): unbiased running mean early, then the
        // configured EMA once enough events have accrued. Removes cold-start bias where
        // a high decay would otherwise leave the profile stuck near the first event.
        double effDecay = Math.min(decay, 1.0 - 1.0 / newCount);
        float[] merged = new float[fresh.length];
        float[] prior  = prev.embedding();
        for (int i = 0; i < fresh.length; i++) {
            merged[i] = (float) (effDecay * prior[i] + (1.0 - effDecay) * fresh[i]);
        }
        return new Baseline(
                prev.entityId(),
                prev.domain(),
                merged,
                newCount,
                clock.instant());
    }
}
