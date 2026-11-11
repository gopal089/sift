package dev.sift.engine.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sift.engine.index.IndexBuilder;
import dev.sift.engine.index.InvertedIndex;
import dev.sift.engine.search.SearchResult.Hit;
import dev.sift.engine.search.SearchResult.MatchMode;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SearcherTest {

    private static InvertedIndex index;
    private static Searcher searcher;

    @BeforeAll
    static void buildIndex() {
        IndexBuilder b = new IndexBuilder();
        b.addDocument(10, "Photosynthesis", "Photosynthesis is how green plants make food from sunlight.");
        b.addDocument(11, "Plant", "A plant is a living thing. Plants grow in soil.");
        b.addDocument(12, "Hamlet", "Hamlet is a play by William Shakespeare. To be, or not to be, that is the question.");
        b.addDocument(13, "The Who", "The Who are an English rock band.");
        b.addDocument(14, "History of India", "The history of India covers thousands of years.");
        b.addDocument(15, "India", "India is a country in South Asia.");
        b.addDocument(16, "Sun", "The Sun gives light. Sunlight helps plants grow.");
        b.addDocument(17, "Twin A", "zebra");
        b.addDocument(18, "Twin B", "zebra");
        index = b.build();
        searcher = new Searcher(index);
    }

    private static List<String> titles(SearchResult r) {
        return r.hits().stream().map(Hit::title).toList();
    }

    @Test
    void singleTermFindsTheArticle() {
        SearchResult r = searcher.search("photosynthesis", 10);
        assertEquals(MatchMode.AND, r.mode());
        assertEquals(1, r.totalMatches());
        assertEquals(List.of("Photosynthesis"), titles(r));
        assertEquals(10, r.hits().get(0).wikiId());
    }

    @Test
    void andRequiresEveryTerm() {
        // "plants" is in Photosynthesis, Plant and Sun, but only Photosynthesis also has "green"
        SearchResult r = searcher.search("green plants", 10);
        assertEquals(MatchMode.AND, r.mode());
        assertEquals(List.of("Photosynthesis"), titles(r));
    }

    @Test
    void fallsBackToOrWhenNoDocumentHasAllTerms() {
        SearchResult r = searcher.search("photosynthesis hamlet", 10);
        assertEquals(MatchMode.OR, r.mode());
        assertEquals(2, r.totalMatches());
        assertEquals(List.of("Hamlet", "Photosynthesis").stream().sorted().toList(), titles(r).stream().sorted().toList());
    }

    @Test
    void unknownWordFallsBackToOrOverTheKnownWords() {
        SearchResult r = searcher.search("photosynthesis qwertyuiop", 10);
        assertEquals(MatchMode.OR, r.mode());
        assertEquals(List.of("Photosynthesis"), titles(r));
    }

    @Test
    void onlyUnknownWordsGiveNoResults() {
        SearchResult r = searcher.search("qwertyuiop", 10);
        assertEquals(0, r.totalMatches());
        assertEquals(List.of(), r.hits());
    }

    @Test
    void emptyOrSymbolOnlyQueryGivesNone() {
        assertEquals(MatchMode.NONE, searcher.search("", 10).mode());
        assertEquals(MatchMode.NONE, searcher.search("!!! 🙂", 10).mode());
    }

    // ------------------------------------------------ the stop-word rule, end to end

    @Test
    void toBeOrNotToBeFindsHamlet() {
        SearchResult r = searcher.search("to be or not to be", 10);
        assertEquals(List.of("to", "be", "or", "not"), r.terms());
        assertEquals(MatchMode.AND, r.mode());
        assertEquals("Hamlet", r.hits().get(0).title());
    }

    @Test
    void theWhoFindsTheBand() {
        SearchResult r = searcher.search("the who", 10);
        // default settings: "who" is a question word, so both words are stop words → keep them all
        assertEquals(List.of("the", "who"), r.terms());
        assertEquals("The Who", r.hits().get(0).title());
    }

    @Test
    void theHistoryOfIndiaIgnoresStopWords() {
        SearchResult r = searcher.search("the history of india", 10);
        assertEquals(List.of("histori", "india"), r.terms());
        assertEquals(MatchMode.AND, r.mode());
        assertEquals(List.of("History of India"), titles(r));   // "India" lacks "history"
    }

    // ------------------------------------------------ scores and ranking

    @Test
    void baselineScoreIsTheSumOfBm25TermScores() {
        SearchResult r = new Searcher(index, Ranking.BASELINE).search("history india", 10);
        Hit hit = r.hits().get(0);
        Bm25 bm25 = new Bm25(index.docs().size(), index.docs().averageLength());
        int len = index.docs().length(hit.docId());
        // in "History of India ...": "histori" ×2 (title + text), "india" ×2 (title + text)
        double expected = bm25.termScore(bm25.idf(index.documentFrequency("histori")), 2, len)
                + bm25.termScore(bm25.idf(index.documentFrequency("india")), 2, len);
        assertEquals(expected, hit.score(), 1e-12);
    }

    @Test
    void defaultScoreIsBodyBm25PlusTwiceTitleBm25() {
        Hit hit = searcher.search("history india", 10).hits().get(0);
        var docs = index.docs();
        Bm25 body = new Bm25(docs.size(), docs.averageBodyLength(), 1.2, 0.3);
        Bm25 title = new Bm25(docs.size(), docs.averageTitleLength(), 1.2, 0.75);
        double expected = 0;
        // "History of India": each word once in the title and once in the body
        for (String term : List.of("histori", "india")) {
            double idf = Bm25.idf(docs.size(), index.documentFrequency(term));
            expected += body.termScore(idf, 1, docs.bodyLength(hit.docId()))
                    + 2.0 * title.termScore(idf, 1, docs.titleLength(hit.docId()));
        }
        assertEquals(expected, hit.score(), 1e-12);
    }

    @Test
    void resultsAreSortedAndLimitedToK() {
        SearchResult r = searcher.search("plants soil sunlight", 2);
        assertEquals(MatchMode.OR, r.mode());   // no single doc has all three ("soil" is only in Plant)
        assertEquals(3, r.totalMatches());     // Photosynthesis, Plant, Sun
        assertEquals(2, r.hits().size());
        assertTrue(r.hits().get(0).score() >= r.hits().get(1).score());
    }

    @Test
    void equalScoresAreOrderedByDocId() {
        assertEquals(List.of("Twin A", "Twin B"), titles(searcher.search("zebra", 10)));
    }
}
