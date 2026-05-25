package io.conclave.baseline.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * Embeddings produced by the in-JVM {@code all-MiniLM-L6-v2} model bundled by
 * langchain4j. 384 dimensions, runs on CPU via ONNX/DJL, no Python sidecar required.
 *
 * <p>The model is thread-safe and reusable across requests, so we instantiate exactly
 * one per JVM. The first call warms the ONNX session (~100ms cold start on Apple M3);
 * subsequent calls are sub-millisecond per short text on modern hardware.
 */
@Component
public class AllMiniLmEmbeddingService implements EmbeddingService {

    /** all-MiniLM-L6-v2 ships 384-dim vectors. Burned in as a constant for fast checks. */
    public static final int DIMENSIONS = 384;

    private final EmbeddingModel model;

    public AllMiniLmEmbeddingService() {
        this.model = new AllMiniLmL6V2EmbeddingModel();
    }

    @Override
    public float[] embed(String text) {
        Embedding embedding = model.embed(TextSegment.from(text)).content();
        return embedding.vector();
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }
}
