package dev.sift.engine.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RankingTest {

    @Test
    void parsesFullSpec() {
        Ranking r = Ranking.parse("bm25f:w=3,bb=0.5,bt=0.6,k1=1.5");
        assertEquals(new Ranking(Ranking.Model.BM25F, 1.5, 0.5, 0.6, 3.0), r);
        assertEquals("BM25F k1=1.5 w_title=3 b_title=0.6 b_body=0.5", r.toString());
    }

    @Test
    void missingKeysKeepBaselineValues() {
        assertEquals(Ranking.BASELINE, Ranking.parse("bm25"));
        assertEquals(new Ranking(Ranking.Model.BM25_SUM, 1.2, 0.75, 0.75, 2.0), Ranking.parse("bm25sum:w=2"));
    }

    @Test
    void rejectsBadSpecs() {
        assertThrows(IllegalArgumentException.class, () -> Ranking.parse("tfidf"));
        assertThrows(IllegalArgumentException.class, () -> Ranking.parse("bm25f:x=1"));
        assertThrows(IllegalArgumentException.class, () -> Ranking.parse("bm25f:w"));
        assertThrows(IllegalArgumentException.class, () -> Ranking.parse("bm25f:bb=1.5"));
        assertThrows(IllegalArgumentException.class, () -> Ranking.parse("bm25f:w=-1"));
    }
}
