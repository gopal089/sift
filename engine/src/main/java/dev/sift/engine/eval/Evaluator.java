package dev.sift.engine.eval;

import dev.sift.engine.index.DocTable;
import dev.sift.engine.search.SearchResult;
import dev.sift.engine.search.SearchResult.Hit;
import dev.sift.engine.search.SearchResult.MatchMode;
import dev.sift.engine.search.Searcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs every eval query through the real {@link Searcher} and records the rank of the expected article.
 *
 * <p>It asks the searcher for the <i>full</i> ranking (K = number of documents), so the rank is exact even
 * when the article is far down (e.g. #458). The searcher's order is deterministic (equal scores: smaller doc
 * id first), so the rank here is exactly the position a user would see.
 */
public final class Evaluator {

    /** What happened to one query. {@code rank} is {@link RankMetrics#NOT_FOUND} if the article did not match. */
    public record Outcome(EvalQuery query, List<String> terms, MatchMode mode, int matches, int rank, String topTitle) {
    }

    private final DocTable docs;
    private final Map<String, Integer> docIdByTitle;

    public Evaluator(DocTable docs) {
        this.docs = docs;
        this.docIdByTitle = new HashMap<>(docs.size() * 2);
        for (int d = 0; d < docs.size(); d++) {
            if (docIdByTitle.putIfAbsent(docs.title(d), d) != null) {
                throw new IllegalStateException("duplicate title: " + docs.title(d));
            }
        }
    }

    /** Fails loudly if any expected title is not an article, so a typo can never count as "not found". */
    public void checkExpectedTitlesExist(List<EvalQuery> queries) {
        List<String> missing = new ArrayList<>();
        for (EvalQuery q : queries) {
            if (!docIdByTitle.containsKey(q.expected())) {
                missing.add("#" + q.id() + " \"" + q.expected() + "\"");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("expected articles not in the index: " + missing);
        }
    }

    public List<Outcome> run(Searcher searcher, List<EvalQuery> queries) {
        checkExpectedTitlesExist(queries);
        List<Outcome> out = new ArrayList<>(queries.size());
        for (EvalQuery q : queries) {
            int expectedDoc = docIdByTitle.get(q.expected());
            SearchResult result = searcher.search(q.query(), docs.size());
            int rank = RankMetrics.NOT_FOUND;
            List<Hit> hits = result.hits();
            for (int i = 0; i < hits.size(); i++) {
                if (hits.get(i).docId() == expectedDoc) {
                    rank = i + 1;
                    break;
                }
            }
            String top = hits.isEmpty() ? "" : hits.get(0).title();
            out.add(new Outcome(q, result.terms(), result.mode(), result.totalMatches(), rank, top));
        }
        return out;
    }

    /** Overall summary first (key "ALL"), then one per category in the order categories first appear. */
    public static Map<String, RankMetrics.Summary> summarizeByCategory(List<Outcome> outcomes) {
        Map<String, List<Integer>> ranks = new LinkedHashMap<>();
        List<Integer> all = new ArrayList<>();
        for (Outcome o : outcomes) {
            ranks.computeIfAbsent(o.query().category(), c -> new ArrayList<>()).add(o.rank());
            all.add(o.rank());
        }
        Map<String, RankMetrics.Summary> summaries = new LinkedHashMap<>();
        summaries.put("ALL", RankMetrics.summarize(all));
        for (Map.Entry<String, List<Integer>> e : ranks.entrySet()) {
            summaries.put(e.getKey(), RankMetrics.summarize(e.getValue()));
        }
        return summaries;
    }
}
