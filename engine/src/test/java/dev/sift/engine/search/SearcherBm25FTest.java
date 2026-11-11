package dev.sift.engine.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sift.engine.index.DocTable;
import dev.sift.engine.index.IndexBuilder;
import dev.sift.engine.index.InvertedIndex;
import dev.sift.engine.search.SearchResult.Hit;
import dev.sift.engine.search.SearchResult.MatchMode;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The Phase 1 ranking problem in miniature: a long main article loses to a short article under plain BM25. */
class SearcherBm25FTest {

    private static final Ranking BM25F_W3 = Ranking.parse("bm25f:w=3");
    private static InvertedIndex index;

    @BeforeAll
    static void build() {
        // a long "main article": 3 mentions of chess in ~400 words
        String filler = "the game has a long history and many famous players from many countries ".repeat(30);
        IndexBuilder b = new IndexBuilder();
        b.addDocument(1, "Chess", "Chess is a board game for two players. " + filler + " chess openings chess rules");
        // a short article that mentions chess once in its body, not in its title
        // (like "Ecuadorian–Peruvian War" beating "World War II" for "world war 2" in Phase 1)
        // Note: a short article with the word in its title AND twice in a 10-word body ("Chess club") still
        // beats "Chess" under BM25F w=3. That is fair: it is strong evidence, and the grid decides w on real data.
        b.addDocument(2, "Board game", "Chess and Go are board games played by two people.");
        // unrelated short documents, so averages and IDF look like a real collection
        b.addDocument(3, "Tennis", "Tennis is a sport played with a racket and a ball.");
        b.addDocument(4, "Football", "Football is a team sport played with a ball.");
        b.addDocument(5, "Piano", "The piano is a musical instrument with keys.");
        b.addDocument(6, "River", "A river is a large natural stream of water.");
        index = b.build();
    }

    private static List<String> titles(SearchResult r) {
        return r.hits().stream().map(Hit::title).toList();
    }

    @Test
    void plainBm25PrefersTheShortArticle() {
        assertEquals(List.of("Board game", "Chess"), titles(new Searcher(index, Ranking.BASELINE).search("chess", 10)));
    }

    @Test
    void bm25fPutsTheMainArticleFirst() {
        assertEquals(List.of("Chess", "Board game"), titles(new Searcher(index, BM25F_W3).search("chess", 10)));
    }

    @Test
    void hitScoreIsTheBm25fFormulaWithTheDocTableLengths() {
        Hit hit = new Searcher(index, BM25F_W3).search("chess", 10).hits().get(0);
        DocTable docs = index.docs();
        Bm25F f = new Bm25F(1.2, 3.0, 0.75, 0.75, docs.averageTitleLength(), docs.averageBodyLength());
        double idf = Bm25.idf(docs.size(), index.documentFrequency("chess"));
        // "Chess": once in the title, 3 times in the body
        double expected = f.termScore(idf, 1, docs.titleLength(hit.docId()), 3, docs.bodyLength(hit.docId()));
        assertEquals(expected, hit.score(), 1e-12);
    }

    @Test
    void andAndOrPathsGiveTheSameScore() {
        Searcher s = new Searcher(index, BM25F_W3);
        SearchResult and = s.search("chess", 10);
        SearchResult or = s.search("chess qwertyuiop", 10);   // unknown word forces the OR path
        assertEquals(MatchMode.AND, and.mode());
        assertEquals(MatchMode.OR, or.mode());
        assertEquals(and.hits(), or.hits());
    }

    @Test
    void sumVariantScoresHigherThanBm25fBecauseItCountsTwice() {
        double f = new Searcher(index, BM25F_W3).search("chess", 10).hits().get(0).score();
        double sum = new Searcher(index, Ranking.parse("bm25sum:w=3")).search("chess", 10).hits().get(0).score();
        assertTrue(sum > f);
    }

}
