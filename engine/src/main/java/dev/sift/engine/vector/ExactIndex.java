package dev.sift.engine.vector;

import dev.sift.engine.search.ScoredDoc;
import dev.sift.engine.search.TopKHeap;
import java.util.List;

/**
 * Exact search over one or more vectors per article. With two vectors (e.g. "opening 60 words" and
 * "first 200 words") a document's similarity is the <b>maximum</b> of its vectors' similarities: the article
 * counts as close if <i>any</i> of its parts is close to the query.
 */
public final class ExactIndex implements SemanticIndex {

    private final VectorStore[] stores;   // stores[v] row d = vector v of document d
    private final String name;

    public ExactIndex(String name, VectorStore... stores) {
        for (VectorStore s : stores) {
            if (s.size() != stores[0].size() || s.dims() != stores[0].dims()) {
                throw new IllegalArgumentException("all vector sets must have the same size and dims");
            }
        }
        this.stores = stores.clone();
        this.name = name;
    }

    @Override
    public List<ScoredDoc> top(float[] q, int k) {
        TopKHeap heap = new TopKHeap(k);
        int n = stores[0].size();
        for (int d = 0; d < n; d++) {
            float best = stores[0].dot(q, d);
            for (int v = 1; v < stores.length; v++) {
                best = Math.max(best, stores[v].dot(q, d));
            }
            heap.offer(d, best);
        }
        return heap.toSortedList();
    }

    @Override
    public String description() {
        return "exact " + name + " (" + stores.length + " vector" + (stores.length > 1 ? "s, max" : "") + " per article, "
                + stores[0].kind() + ")";
    }

    @Override
    public long vectorBytes() {
        long b = 0;
        for (VectorStore s : stores) {
            b += s.bytes();
        }
        return b;
    }
}
