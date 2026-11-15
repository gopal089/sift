package dev.sift.engine.card;

import dev.sift.engine.search.ScoredDoc;
import dev.sift.engine.vector.SemanticIndex;
import dev.sift.engine.vector.VectorStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * "Related articles": the articles nearest in meaning to one article, found with our own HNSW index.
 *
 * <p>No query has to be embedded: the article's own stored vector is the query. Its nearest neighbour is
 * always the article itself (similarity 1), so that one is skipped, together with every article already
 * shown on the results page. Cost: one HNSW search, the same kind Smart mode already does for each query.
 */
public final class RelatedArticles {

    private final VectorStore vectors;
    private final SemanticIndex index;

    public RelatedArticles(VectorStore vectors, SemanticIndex index) {
        this.vectors = vectors;
        this.index = index;
    }

    /**
     * Up to {@code k} articles nearest to {@code docId}, best first, never {@code docId} itself or one of
     * {@code exclude}.
     */
    public List<ScoredDoc> of(int docId, Set<Integer> exclude, int k) {
        float[] v = new float[vectors.dims()];
        vectors.copy(docId, v);
        // ask for enough that k remain even if the article itself and every excluded one are among them
        List<ScoredDoc> near = index.top(v, k + exclude.size() + 1);
        List<ScoredDoc> out = new ArrayList<>(k);
        for (ScoredDoc d : near) {
            if (d.docId() != docId && !exclude.contains(d.docId())) {
                out.add(d);
                if (out.size() == k) {
                    break;
                }
            }
        }
        return out;
    }
}
