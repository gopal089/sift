package dev.sift.engine.search;

import dev.sift.engine.embed.Embedder;
import dev.sift.engine.index.DocTable;
import dev.sift.engine.search.SearchResult.Hit;
import dev.sift.engine.search.SearchResult.MatchMode;
import dev.sift.engine.text.Tokenizer;
import dev.sift.engine.vector.Hnsw;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Smart search: find articles by meaning.
 * <ul>
 *   <li><b>SEMANTIC:</b> embed the query, take the 100 most similar article vectors from HNSW.</li>
 *   <li><b>HYBRID:</b> the keyword top 100 and the semantic top 100, merged with Reciprocal Rank Fusion:
 *       {@code score(d) = Σ 1 / (60 + rank of d in each list that has it)}. Only ranks are used, so the
 *       very different score scales (BM25 ≈ 5–40, cosine ≈ 0.2–0.8) never need to be matched.</li>
 * </ul>
 * Worked example (k = 60): "Photosynthesis" is #3 by keywords and #1 by meaning → 1/63 + 1/61 = 0.0323.
 * "Plant" is #1 by keywords only → 1/61 = 0.0164. Being good in both lists beats being best in one.
 */
public final class SmartSearcher implements QueryRanker {

    public enum Mode { SEMANTIC, HYBRID }

    public static final int LIST_SIZE = 100;
    public static final int RRF_K = 60;

    /** A result plus where the time went (shown in the website's stats panel). */
    public record Timed(SearchResult result, double embedMs, double vectorMs, double keywordMs) {
    }

    private final Searcher keyword;
    private final Embedder embedder;
    private final Hnsw graph;
    private final DocTable docs;
    private final Mode mode;
    private final int efSearch;
    private final String vectorKind;

    public SmartSearcher(Searcher keyword, Embedder embedder, Hnsw graph, DocTable docs, Mode mode,
                         int efSearch, String vectorKind) {
        this.keyword = keyword;
        this.embedder = embedder;
        this.graph = graph;
        this.docs = docs;
        this.mode = mode;
        this.efSearch = efSearch;
        this.vectorKind = vectorKind;
    }

    public Mode mode() {
        return mode;
    }

    @Override
    public String description() {
        String semantic = String.format("HNSW M=%d efC=%d efSearch=%d (%s vectors), top %d", graph.m(),
                graph.efConstruction(), Math.max(efSearch, LIST_SIZE), vectorKind, LIST_SIZE);
        return mode == Mode.SEMANTIC ? "semantic: " + semantic
                : "hybrid RRF k=" + RRF_K + ": keyword top " + LIST_SIZE + " [" + keyword.description() + "] + " + semantic;
    }

    @Override
    public SearchResult search(String query, int k) {
        return searchTimed(query, k).result();
    }

    public Timed searchTimed(String query, int k) {
        // The keyword terms are still needed: snippets bold them, and "no letters or digits" stays an empty answer.
        List<String> terms = Tokenizer.queryTerms(query, keyword.ranking().dropQuestionWords());
        if (terms.isEmpty()) {
            return new Timed(new SearchResult(terms, MatchMode.NONE, 0, List.of()), 0, 0, 0);
        }
        long t0 = System.nanoTime();
        float[] q = embedder.embed(query);
        long t1 = System.nanoTime();
        // HNSW keeps max(efSearch, 100) candidates, so the top 100 list is as good as the top 10 measured
        List<ScoredDoc> semantic = graph.search(q, LIST_SIZE, Math.max(efSearch, LIST_SIZE));
        long t2 = System.nanoTime();
        double embedMs = (t1 - t0) / 1e6;
        double vectorMs = (t2 - t1) / 1e6;

        if (mode == Mode.SEMANTIC) {
            List<Hit> hits = new ArrayList<>();
            for (int i = 0; i < semantic.size() && i < k; i++) {
                hits.add(hit(semantic.get(i)));
            }
            return new Timed(new SearchResult(terms, MatchMode.SEMANTIC, semantic.size(), hits), embedMs, vectorMs, 0);
        }

        long t3 = System.nanoTime();
        SearchResult byKeyword = keyword.search(query, LIST_SIZE);
        double keywordMs = (System.nanoTime() - t3) / 1e6;
        List<ScoredDoc> keywordList = new ArrayList<>();
        for (Hit h : byKeyword.hits()) {
            keywordList.add(new ScoredDoc(h.docId(), h.score()));
        }
        List<ScoredDoc> fused = fuse(List.of(keywordList, semantic), RRF_K);
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < fused.size() && i < k; i++) {
            hits.add(hit(fused.get(i)));
        }
        return new Timed(new SearchResult(terms, MatchMode.HYBRID, fused.size(), hits), embedMs, vectorMs, keywordMs);
    }

    /**
     * Reciprocal Rank Fusion. Each list is best first; a document's fused score is the sum of
     * {@code 1 / (k + rank)} over the lists that contain it (rank starts at 1).
     * Result: best first, equal scores → smaller doc id first.
     */
    public static List<ScoredDoc> fuse(List<List<ScoredDoc>> lists, int k) {
        Map<Integer, Double> score = new HashMap<>();
        for (List<ScoredDoc> list : lists) {
            for (int r = 0; r < list.size(); r++) {
                score.merge(list.get(r).docId(), 1.0 / (k + r + 1), Double::sum);
            }
        }
        List<ScoredDoc> out = new ArrayList<>(score.size());
        score.forEach((doc, s) -> out.add(new ScoredDoc(doc, s)));
        out.sort((a, b) -> a.score() != b.score() ? Double.compare(b.score(), a.score())
                : Integer.compare(a.docId(), b.docId()));
        return out;
    }

    private Hit hit(ScoredDoc sd) {
        return new Hit(sd.docId(), docs.wikiId(sd.docId()), docs.title(sd.docId()), sd.score());
    }
}
