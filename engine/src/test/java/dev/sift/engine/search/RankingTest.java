package dev.sift.engine.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RankingTest {

    @Test
    void parsesFullSpec() {
        Ranking r = Ranking.parse("bm25f:w=3,bb=0.5,bt=0.6,k1=1.5");
        assertEquals(new Ranking(Ranking.Model.BM25F, 1.5, 0.5, 0.6, 3.0, 0.0, 0.0, false, false), r);
        assertEquals("BM25F k1=1.5 w_title=3 b_title=0.6 b_body=0.5 | AND, else OR", r.toString());
    }

    @Test
    void parsesAliasWeightSoftAndAndQuestionWords() {
        Ranking r = Ranking.parse("bm25sum:w=2,bb=0.3,wa=1,soft=1,qstop=1");
        assertEquals(new Ranking(Ranking.Model.BM25_SUM, 1.2, 0.3, 0.75, 2.0, 1.0, 0.0, true, true), r);
        assertEquals("BM25(body) + w*BM25(title) k1=1.2 w=2 b_title=0.75 b_body=0.3 + alias bonus w_alias=1"
                + " | soft AND | question words dropped", r.toString());
    }

    @Test
    void missingKeysKeepBaselineValues() {
        assertEquals(Ranking.BASELINE, Ranking.parse("bm25"));
        assertEquals(new Ranking(Ranking.Model.BM25_SUM, 1.2, 0.75, 0.75, 2.0, 0.0, 0.0, false, false),
                Ranking.parse("bm25sum:w=2"));
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
