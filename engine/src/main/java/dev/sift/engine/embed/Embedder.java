package dev.sift.engine.embed;

/** Text → length-1 meaning vector. The real one is {@link QueryEmbedder}; tests use a small fake. */
public interface Embedder {

    float[] embed(String text);
}
