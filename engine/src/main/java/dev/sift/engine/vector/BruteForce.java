package dev.sift.engine.vector;

import dev.sift.engine.search.ScoredDoc;
import dev.sift.engine.search.TopKHeap;
import java.util.List;

/**
 * Exact vector search: compare the query with every document and keep the K most similar in a
 * size-K min-heap. O(N × dims) per query. Slow-ish but always right, so it is the reference that
 * HNSW's recall is measured against.
 */
public final class BruteForce {

    private BruteForce() {
    }

    /** The K most similar documents, best first (equal scores: smaller doc id first). */
    public static List<ScoredDoc> search(VectorStore vectors, float[] query, int k) {
        TopKHeap heap = new TopKHeap(k);
        for (int d = 0; d < vectors.size(); d++) {
            heap.offer(d, vectors.dot(query, d));
        }
        return heap.toSortedList();
    }
}
