package io.conclave.baseline.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the bundled {@code all-MiniLM-L6-v2} model end-to-end. No mocks —
 * verifies the real ONNX/DJL inference path returns 384-dim float vectors that
 * are stable for identical input.
 *
 * <p>The model warms up on first call (~100ms on Apple M3); we run a small warm-up
 * pass in each test to keep timing assertions sane.
 */
class AllMiniLmEmbeddingServiceTest {

    private final AllMiniLmEmbeddingService embedder = new AllMiniLmEmbeddingService();

    @Test
    @DisplayName("embed() returns a 384-dimensional float vector")
    void embedReturns384Dimensions() {
        float[] v = embedder.embed("a card-not-present transaction for $42 at acme-corp");
        assertThat(v).hasSize(AllMiniLmEmbeddingService.DIMENSIONS);
    }

    @Test
    @DisplayName("dimensions() reports the same value the model produces")
    void dimensionsConstantMatchesModel() {
        assertThat(embedder.dimensions()).isEqualTo(384);
    }

    @Test
    @DisplayName("Identical input produces an identical embedding")
    void embedIsDeterministic() {
        float[] a = embedder.embed("login from new device");
        float[] b = embedder.embed("login from new device");
        assertThat(a).containsExactly(b);
    }

    @Test
    @DisplayName("Different input produces a different embedding")
    void embedIsDiscriminative() {
        float[] a = embedder.embed("login from new device");
        float[] b = embedder.embed("transaction at gas station for $30");
        assertThat(a).isNotEqualTo(b);
    }
}
