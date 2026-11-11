package dev.sift.engine.vector;

import dev.sift.engine.search.ScoredDoc;
import java.util.List;

/** "Which articles mean something close to this query vector?" Exact ({@link ExactIndex}) or HNSW ({@link HnswIndex}). */
public interface SemanticIndex {

    /** The {@code k} most similar documents (doc ids, each at most once), best first. */
    List<ScoredDoc> top(float[] query, int k);

    /** One line for results files and /stats. */
    String description();

    /** Bytes of all vectors searched. */
    long vectorBytes();
}
