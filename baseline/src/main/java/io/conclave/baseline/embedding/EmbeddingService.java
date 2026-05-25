package io.conclave.baseline.embedding;

/**
 * Computes a fixed-dimensional dense embedding for a textualized event. M3 ships one
 * implementation ({@link AllMiniLmEmbeddingService}); the interface exists so future
 * milestones can swap in a domain-specific model without touching the rest of the
 * service.
 */
public interface EmbeddingService {

    /**
     * Embed a single text input.
     *
     * @return a freshly-allocated array of length {@link #dimensions()}. Caller may
     *         mutate the returned array without affecting subsequent calls.
     */
    float[] embed(String text);

    /** Dimensionality of vectors returned by {@link #embed}. */
    int dimensions();
}
