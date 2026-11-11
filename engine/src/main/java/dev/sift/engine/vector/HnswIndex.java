package dev.sift.engine.vector;

import dev.sift.engine.search.ScoredDoc;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HNSW search as a {@link SemanticIndex}. If the graph holds several vectors per article
 * ({@link StackedVectors}), node {@code i} is document {@code i % documents}; we ask the graph for
 * {@code k × vectorsPerDoc} nodes and keep each document's best (first) appearance, which is the max similarity.
 */
public final class HnswIndex implements SemanticIndex {

    private final Hnsw graph;
    private final int efSearch;
    private final int documents;
    private final int vectorsPerDoc;
    private final String name;

    public HnswIndex(String name, Hnsw graph, int efSearch, int documents) {
        if (graph.size() % documents != 0) {
            throw new IllegalArgumentException("graph size " + graph.size() + " is not a multiple of " + documents);
        }
        this.graph = graph;
        this.efSearch = efSearch;
        this.documents = documents;
        this.vectorsPerDoc = graph.size() / documents;
        this.name = name;
    }

    @Override
    public List<ScoredDoc> top(float[] q, int k) {
        int nodes = k * vectorsPerDoc;
        List<ScoredDoc> hits = graph.search(q, nodes, Math.max(efSearch, nodes));
        if (vectorsPerDoc == 1) {
            return hits;
        }
        List<ScoredDoc> out = new ArrayList<>(k);
        Set<Integer> seen = new HashSet<>();
        for (ScoredDoc h : hits) {
            int doc = h.docId() % documents;
            if (seen.add(doc)) {
                out.add(new ScoredDoc(doc, h.score()));
                if (out.size() == k) {
                    break;
                }
            }
        }
        return out;
    }

    public int efSearch() {
        return efSearch;
    }

    @Override
    public String description() {
        return String.format("HNSW %s M=%d efC=%d efSearch=%d, %d vector%s per article (%s)", name, graph.m(),
                graph.efConstruction(), efSearch, vectorsPerDoc, vectorsPerDoc > 1 ? "s, max" : "",
                graph.vectorKind());
    }

    @Override
    public long vectorBytes() {
        return graph.vectorBytes();
    }
}
