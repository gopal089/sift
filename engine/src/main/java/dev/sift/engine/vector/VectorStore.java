package dev.sift.engine.vector;

/**
 * All article vectors, row {@code d} = doc id {@code d}. Every vector has length 1, so a dot product is
 * the cosine similarity: 1 = same direction (same meaning), 0 = unrelated.
 */
public interface VectorStore {

    int size();

    int dims();

    /** Similarity of a query vector and document {@code doc}. */
    float dot(float[] query, int doc);

    /** Similarity of two documents (used while building the HNSW graph). */
    float dot(int a, int b);

    /** Copies (and for int8: decodes) document {@code doc}'s vector into {@code out}. */
    void copy(int doc, float[] out);

    /** Bytes the vectors take (on disk and, once read, in memory). */
    long bytes();

    /** "float32" or "int8". */
    String kind();
}
