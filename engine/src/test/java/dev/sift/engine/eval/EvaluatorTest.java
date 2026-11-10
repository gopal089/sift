package dev.sift.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sift.engine.index.IndexBuilder;
import dev.sift.engine.index.InvertedIndex;
import dev.sift.engine.search.SearchResult;
import dev.sift.engine.search.SearchResult.MatchMode;
import dev.sift.engine.search.Searcher;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EvaluatorTest {

    private static InvertedIndex index;
    private static Searcher searcher;
    private static Evaluator evaluator;

    @BeforeAll
    static void build() {
        IndexBuilder b = new IndexBuilder();
        b.addDocument(1, "Photosynthesis", "Photosynthesis is how green plants make food from sunlight.");
        b.addDocument(2, "Plant", "A plant is a living thing. Plants grow in soil and need sunlight.");
        b.addDocument(3, "Sun", "The Sun gives light. Sunlight helps plants grow.");
        b.addDocument(4, "Moon", "The Moon goes around the Earth.");
        index = b.build();
        searcher = new Searcher(index);
        evaluator = new Evaluator(index.docs());
    }

    private static EvalQuery q(int id, String query, String expected, String category) {
        return new EvalQuery(id, query, expected, category, "");
    }

    @Test
    void rankIsThePositionInTheSearcherOwnFullRanking() {
        // "sunlight" matches Photosynthesis, Plant and Sun; whatever order BM25 gives, the evaluator must agree.
        SearchResult full = searcher.search("sunlight", 10);
        assertEquals(3, full.totalMatches());
        for (int i = 0; i < full.hits().size(); i++) {
            String title = full.hits().get(i).title();
            Evaluator.Outcome o = evaluator.run(searcher, List.of(q(1, "sunlight", title, "x"))).get(0);
            assertEquals(i + 1, o.rank(), "rank of " + title);
            assertEquals(full.hits().get(0).title(), o.topTitle());
            assertEquals(MatchMode.AND, o.mode());
            assertEquals(3, o.matches());
        }
    }

    @Test
    void articleThatDoesNotMatchIsNotFound() {
        Evaluator.Outcome o = evaluator.run(searcher, List.of(q(1, "sunlight", "Moon", "x"))).get(0);
        assertEquals(RankMetrics.NOT_FOUND, o.rank());
    }

    @Test
    void queryWithNoResultsIsNotFound() {
        Evaluator.Outcome o = evaluator.run(searcher, List.of(q(1, "zzzzqqq", "Moon", "x"))).get(0);
        assertEquals(RankMetrics.NOT_FOUND, o.rank());
        assertEquals("", o.topTitle());
    }

    @Test
    void misspelledExpectedTitleFailsLoudly() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> evaluator.run(searcher, List.of(q(7, "moon", "The Moon", "x"))));
        assertTrue(e.getMessage().contains("#7"), e.getMessage());
    }

    @Test
    void summaryHasAllFirstThenCategoriesInOrderOfAppearance() {
        List<Evaluator.Outcome> outcomes = evaluator.run(searcher, List.of(
                q(1, "photosynthesis", "Photosynthesis", "single"),   // rank 1
                q(2, "moon earth", "Moon", "multi"),                   // rank 1
                q(3, "sunlight", "Moon", "single")));                  // not found
        Map<String, RankMetrics.Summary> s = Evaluator.summarizeByCategory(outcomes);
        assertEquals(List.of("ALL", "single", "multi"), List.copyOf(s.keySet()));
        assertEquals(2 / 3.0, s.get("ALL").mrr(), 1e-9);
        assertEquals(0.5, s.get("single").mrr(), 1e-9);
        assertEquals(1.0, s.get("multi").mrr(), 1e-9);
    }
}
