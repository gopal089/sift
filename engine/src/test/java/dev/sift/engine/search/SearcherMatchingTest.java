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

/** Alias bonus, soft AND and question-word stop words, on a tiny index. */
class SearcherMatchingTest {

    private static final String SUM = "bm25sum:w=2,bb=0.3";
    private static InvertedIndex index;

    @BeforeAll
    static void build() {
        IndexBuilder b = new IndexBuilder();
        b.addDocument(1, "United States", "A country in North America.", List.of("USA", "U.S.A."));
        b.addDocument(2, "Boing", "A song that was popular in the usa and the usa charts.", List.of());
        b.addDocument(3, "Alexander Graham Bell", "Bell was a scientist. He invented the telephone.", List.of());
        b.addDocument(4, "Telephone", "A telephone is a tool. People who are far away can talk.", List.of());
        b.addDocument(5, "Granville Woods", "Woods was a man who invented a telephone and telegraph system.", List.of());
        b.addDocument(6, "Earthquake", "An earthquake is the shaking of the ground.", List.of());
        index = b.build();
    }

    private static List<String> titles(SearchResult r) {
        return r.hits().stream().map(Hit::title).toList();
    }

    private static Searcher searcher(String spec) {
        return new Searcher(index, Ranking.parse(spec));
    }

    // ------------------------------------------------ alias field

    @Test
    void aliasWordMatchesEvenWithZeroWeightButRanksLow() {
        // "usa" is only an alias word of United States: it matches (AND works) but adds no score
        SearchResult r = searcher(SUM).search("usa", 10);
        assertEquals(List.of("Boing", "United States"), titles(r));
        assertEquals(0.0, r.hits().get(1).score(), 0.0);
    }

    @Test
    void aliasBonusIsWeightTimesIdfAndLiftsTheArticle() {
        SearchResult r = searcher(SUM + ",wa=2").search("usa", 10);
        assertEquals("United States", r.hits().get(0).title());
        double idf = Bm25.idf(index.docs().size(), index.documentFrequency("usa"));
        assertEquals(2 * idf, r.hits().get(0).score(), 1e-12);
    }

    // ------------------------------------------------ PageRank boost

    @Test
    void pageRankMultipliesTheScoreAndCanChangeTheOrder() {
        IndexBuilder b = new IndexBuilder();
        b.addDocument(1, "Small page", "zebra animal", List.of());
        b.addDocument(2, "Big page", "zebra and other things", List.of());
        b.addDocument(3, "Other", "nothing here", List.of());
        b.setPageRank(new double[] {0.001, 0.1, 0.01});   // boosts on the log scale: 0, 1, 0.5
        InvertedIndex ranked = b.build();
        assertEquals(1.0, ranked.docs().pageRankBoost(1), 1e-12);

        SearchResult plain = new Searcher(ranked, Ranking.parse(SUM)).search("zebra", 10);
        assertEquals(List.of("Small page", "Big page"), titles(plain));
        double small = plain.hits().get(0).score();
        double big = plain.hits().get(1).score();

        SearchResult boosted = new Searcher(ranked, Ranking.parse(SUM + ",a=0.5")).search("zebra", 10);
        assertEquals(List.of("Big page", "Small page"), titles(boosted));
        assertEquals(big * 1.5, boosted.hits().get(0).score(), 1e-12);   // boost 1 → × (1 + 0.5 × 1)
        assertEquals(small, boosted.hits().get(1).score(), 1e-12);       // boost 0 → unchanged
    }

    @Test
    void withoutPageRankInTheIndexAlphaChangesNothing() {
        assertEquals(searcher(SUM).search("telephone", 10), searcher(SUM + ",a=0.5").search("telephone", 10));
    }

    // ------------------------------------------------ question words

    @Test
    void questionWordsAreRequiredByDefaultAndHideTheAnswer() {
        SearchResult r = searcher(SUM).search("who invented the telephone", 10);
        assertEquals(List.of("who", "invent", "telephon"), r.terms());
        assertEquals(List.of("Granville Woods"), titles(r));   // the only document that also contains "who"
    }

    @Test
    void droppingQuestionWordsFindsEveryDocumentWithTheRealWords() {
        SearchResult r = searcher(SUM + ",qstop=1").search("who invented the telephone", 10);
        assertEquals(List.of("invent", "telephon"), r.terms());
        assertEquals(MatchMode.AND, r.mode());
        assertEquals(2, r.totalMatches());
        assertTrue(titles(r).contains("Alexander Graham Bell"));
    }

    @Test
    void queryOfOnlyStopAndQuestionWordsKeepsThemAll() {
        assertEquals(List.of("the", "who"), searcher(SUM + ",qstop=1").search("the who", 10).terms());
    }

    // ------------------------------------------------ soft AND

    @Test
    void softAndRanksAllTermMatchesFirstThenFewerTerms() {
        SearchResult soft = searcher(SUM + ",soft=1").search("who invented the telephone", 10);
        assertEquals(MatchMode.SOFT_AND, soft.mode());
        // 3 of 3 terms: Granville Woods. 2 of 3: Alexander Graham Bell (invent, telephon), Telephone (who, telephon)
        assertEquals("Granville Woods", soft.hits().get(0).title());
        assertEquals(3, soft.totalMatches());
        assertTrue(titles(soft).indexOf("Alexander Graham Bell") > 0);
        // the tier is removed again: scores are plain sums, far below TIER
        for (Hit h : soft.hits()) {
            assertTrue(h.score() > 0 && h.score() < 1000, "score " + h.score());
        }
    }

    @Test
    void softAndKeepsTheStrictOrderAmongDocumentsWithAllTerms() {
        List<String> strict = titles(searcher(SUM).search("invented telephone", 10));
        List<String> soft = titles(searcher(SUM + ",soft=1").search("invented telephone", 10));
        assertEquals(strict, soft.subList(0, strict.size()));
        assertTrue(soft.size() > strict.size());   // plus "Telephone", which has only one of the words
    }

    @Test
    void softAndScoreMatchesStrictScoreForTheSameDocument() {
        Hit strict = searcher(SUM).search("invented telephone", 10).hits().get(0);
        Hit soft = searcher(SUM + ",soft=1").search("invented telephone", 10).hits().get(0);
        assertEquals(strict.docId(), soft.docId());
        assertEquals(strict.score(), soft.score(), 1e-8);
    }
}
