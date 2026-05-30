package io.conclave.baseline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.conclave.baseline.config.BaselineProperties;
import io.conclave.baseline.domain.Baseline;
import io.conclave.baseline.embedding.EmbeddingService;
import io.conclave.baseline.storage.BaselineRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link BaselineService}. Mocked repo + embedder so EMA arithmetic is
 * verified exactly without any storage or model overhead.
 */
class BaselineServiceTest {

    private static final BaselineProperties PROPS_DECAY_50 =
            new BaselineProperties(0.5, 4);
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-05-25T12:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("get() delegates to the repository")
    void getDelegatesToRepository() {
        BaselineRepository repo = mock(BaselineRepository.class);
        EmbeddingService embedder = stubEmbedder(new float[]{1, 2, 3, 4});
        Baseline existing = new Baseline("e1", "fraud", new float[]{1, 2, 3, 4}, 5, Instant.EPOCH);
        when(repo.find("fraud", "e1")).thenReturn(Optional.of(existing));

        BaselineService svc = new BaselineService(repo, embedder, PROPS_DECAY_50, FIXED_CLOCK);

        assertThat(svc.get("fraud", "e1")).contains(existing);
        verify(repo).find("fraud", "e1");
    }

    @Test
    @DisplayName("update() on an unseen entity creates a fresh baseline with eventCount=1")
    void updateCreatesFreshBaseline() {
        BaselineRepository repo = mock(BaselineRepository.class);
        when(repo.find(anyString(), anyString())).thenReturn(Optional.empty());
        float[] fresh = {0.5f, 0.5f, 0.5f, 0.5f};
        EmbeddingService embedder = stubEmbedder(fresh);

        BaselineService svc = new BaselineService(repo, embedder, PROPS_DECAY_50, FIXED_CLOCK);
        Baseline result = svc.update("fraud", "newCust", "first event");

        assertThat(result.entityId()).isEqualTo("newCust");
        assertThat(result.domain()).isEqualTo("fraud");
        assertThat(result.embedding()).containsExactly(fresh);
        assertThat(result.eventCount()).isEqualTo(1L);
        assertThat(result.lastUpdated()).isEqualTo(FIXED_CLOCK.instant());

        ArgumentCaptor<Baseline> saved = ArgumentCaptor.forClass(Baseline.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue()).isEqualTo(result);
    }

    @Test
    @DisplayName("update() folds the new embedding into the existing baseline via EMA")
    void updateAppliesEma() {
        BaselineRepository repo = mock(BaselineRepository.class);
        float[] prior = {0.0f, 2.0f, 4.0f, 6.0f};
        Baseline existing = new Baseline("e1", "fraud", prior, 3, Instant.EPOCH);
        when(repo.find("fraud", "e1")).thenReturn(Optional.of(existing));

        float[] fresh = {2.0f, 4.0f, 6.0f, 8.0f};
        EmbeddingService embedder = stubEmbedder(fresh);

        BaselineService svc = new BaselineService(repo, embedder, PROPS_DECAY_50, FIXED_CLOCK);
        Baseline result = svc.update("fraud", "e1", "next event");

        // decay=0.5 → merged[i] = 0.5*prior[i] + 0.5*fresh[i]
        assertThat(result.embedding())
                .containsExactly(1.0f, 3.0f, 5.0f, 7.0f);
        assertThat(result.eventCount()).isEqualTo(4L);
        assertThat(result.lastUpdated()).isEqualTo(FIXED_CLOCK.instant());
        assertThat(result.entityId()).isEqualTo("e1");
    }

    @Test
    @DisplayName("EMA decay = 0.0 means the latest embedding wins entirely")
    void emaWithZeroDecayUsesLatestVerbatim() {
        BaselineRepository repo = mock(BaselineRepository.class);
        Baseline existing = new Baseline("e1", "fraud",
                new float[]{9, 9, 9, 9}, 10, Instant.EPOCH);
        when(repo.find(eq("fraud"), eq("e1"))).thenReturn(Optional.of(existing));

        float[] fresh = {1, 2, 3, 4};
        BaselineService svc = new BaselineService(
                repo, stubEmbedder(fresh), new BaselineProperties(0.0, 4), FIXED_CLOCK);

        Baseline result = svc.update("fraud", "e1", "blah");

        assertThat(result.embedding()).containsExactly(fresh);
        assertThat(result.eventCount()).isEqualTo(11L);
    }

    @Test
    @DisplayName("EMA decay near 1.0 means new events barely move a well-established baseline")
    void emaWithHighDecayBarelyMoves() {
        BaselineRepository repo = mock(BaselineRepository.class);
        // eventCount high enough that the count-aware warmup has saturated to the
        // configured decay (warmup is past once n > 1/(1-decay) = 100 here).
        Baseline existing = new Baseline("e1", "fraud",
                new float[]{0, 0, 0, 0}, 500, Instant.EPOCH);
        when(repo.find(anyString(), anyString())).thenReturn(Optional.of(existing));

        float[] fresh = {1, 1, 1, 1};
        BaselineService svc = new BaselineService(
                repo, stubEmbedder(fresh), new BaselineProperties(0.99, 4), FIXED_CLOCK);

        Baseline result = svc.update("fraud", "e1", "blah");

        for (float f : result.embedding()) {
            assertThat(f).isCloseTo(0.01f, within(1e-5f));
        }
    }

    @Test
    @DisplayName("Count-aware warmup averages the early events instead of pinning the first")
    void emaWarmupAveragesEarlyEvents() {
        BaselineRepository repo = mock(BaselineRepository.class);
        // One prior event → this update is the 2nd (n=2). A plain decay=0.85 EMA would
        // give the new event only 15% weight; the warmup uses effDecay = 1 - 1/2 = 0.5,
        // i.e. a true mean of the two events.
        Baseline existing = new Baseline("e1", "fraud",
                new float[]{0, 0, 0, 0}, 1, Instant.EPOCH);
        when(repo.find(anyString(), anyString())).thenReturn(Optional.of(existing));

        float[] fresh = {1, 1, 1, 1};
        BaselineService svc = new BaselineService(
                repo, stubEmbedder(fresh), new BaselineProperties(0.85, 4), FIXED_CLOCK);

        Baseline result = svc.update("fraud", "e1", "second event");

        for (float f : result.embedding()) {
            assertThat(f).isCloseTo(0.5f, within(1e-5f));
        }
        assertThat(result.eventCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Constructor fails fast when embedder dimensions disagree with config")
    void constructorRejectsDimensionMismatch() {
        BaselineRepository repo = mock(BaselineRepository.class);
        EmbeddingService embedder = stubEmbedder(new float[8]); // 8 dims
        BaselineProperties props = new BaselineProperties(0.5, 4); // configured 4

        assertThatThrownBy(() -> new BaselineService(repo, embedder, props, FIXED_CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimensions");
    }

    @Test
    @DisplayName("update() saves exactly once per call; get() never writes")
    void savesExactlyOnce() {
        BaselineRepository repo = mock(BaselineRepository.class);
        when(repo.find(anyString(), anyString())).thenReturn(Optional.empty());
        EmbeddingService embedder = stubEmbedder(new float[]{1, 2, 3, 4});

        BaselineService svc = new BaselineService(repo, embedder, PROPS_DECAY_50, FIXED_CLOCK);

        svc.update("fraud", "x", "evt");
        verify(repo, times(1)).save(any(Baseline.class));

        svc.get("fraud", "x");
        // get() must not trigger a write
        verify(repo, times(1)).save(any(Baseline.class));
    }

    private static EmbeddingService stubEmbedder(float[] returnVector) {
        return new EmbeddingService() {
            @Override public float[] embed(String text) {
                return returnVector.clone();  // defensive copy
            }
            @Override public int dimensions() { return returnVector.length; }
        };
    }
}
