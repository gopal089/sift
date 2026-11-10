package dev.sift.engine.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Bm25FTest {

    private static final double EPS = 1e-9;

    /**
     * Worked example (also in docs/learning/phase-1.md). k1 = 1.2, w_title = 2, b_title = b_body = 0.75,
     * average title length 2, average body length 100, idf = 2.0.
     * The document's title has 3 words and contains the term once; its body has 400 words and contains it 4 times.
     * <pre>
     *   B_title = 1 − 0.75 + 0.75 × 3/2     = 1.375     title part = 2 × 1 / 1.375 = 1.454545
     *   B_body  = 1 − 0.75 + 0.75 × 400/100 = 3.25      body part  = 4 / 3.25      = 1.230769
     *   tf~     = 2.685315
     *   score   = 2.0 × 2.685315 × 2.2 / (1.2 + 2.685315) = 3.041037
     * </pre>
     */
    @Test
    void handWorkedExample() {
        Bm25F f = new Bm25F(1.2, 2.0, 0.75, 0.75, 2.0, 100.0);
        assertEquals(3.041036717062635, f.termScore(2.0, 1, 3, 4, 400), EPS);
    }

    @Test
    void withZeroTitleWeightItIsPlainBm25OverTheBody() {
        Bm25F f = new Bm25F(1.2, 0.0, 0.75, 0.75, 2.0, 100.0);
        Bm25 body = new Bm25(1000, 100.0, 1.2, 0.75);
        for (int tf = 1; tf <= 5; tf++) {
            for (int len : new int[] {10, 100, 5000}) {
                assertEquals(body.termScore(1.7, tf, len), f.termScore(1.7, 1, 3, tf, len), EPS);
            }
        }
    }

    @Test
    void titleOnlyMatchScoresAndGrowsWithWeight() {
        double w1 = new Bm25F(1.2, 1.0, 0.75, 0.75, 2.0, 100.0).termScore(2.0, 1, 2, 0, 50);
        double w3 = new Bm25F(1.2, 3.0, 0.75, 0.75, 2.0, 100.0).termScore(2.0, 1, 2, 0, 50);
        assertTrue(w1 > 0);
        assertTrue(w3 > w1);
    }

    @Test
    void longBodyIsPenalisedButTitleMatchIsNot() {
        Bm25F f = new Bm25F(1.2, 2.0, 0.75, 0.75, 2.0, 100.0);
        // same title (3 words, 1 match), body 100 words vs 5,000 words with the same 4 matches
        double shortBody = f.termScore(2.0, 1, 3, 4, 100);
        double longBody = f.termScore(2.0, 1, 3, 4, 5000);
        assertTrue(longBody < shortBody);
        // ...but the title still keeps the long article well above zero: at least the title-only score
        assertTrue(longBody > f.termScore(2.0, 1, 3, 0, 5000));
    }

    @Test
    void scoreNeverExceedsTheSaturationCeiling() {
        // tf~ / (k1 + tf~) < 1, so a term can never add more than idf × (k1 + 1), however often it appears
        Bm25F f = new Bm25F(1.2, 5.0, 0.75, 0.75, 2.0, 100.0);
        assertTrue(f.termScore(2.0, 50, 1, 10_000, 10) < 2.0 * 2.2);
    }

    @Test
    void sumOfTwoBm25ScoresCountsTheSameEvidenceTwice() {
        // Same document as handWorkedExample: the "sum" variant gives 5.5486, BM25F gives 3.0410.
        // The sum gets two "first mention" bonuses and is no longer capped at idf × (k1 + 1) = 4.4.
        double bt = 1.0 - 0.75 + 0.75 * 3 / 2.0;
        double bb = 1.0 - 0.75 + 0.75 * 400 / 100.0;
        double body = 2.0 * 4 * 2.2 / (4 + 1.2 * bb);
        double title = 2.0 * 1 * 2.2 / (1 + 1.2 * bt);
        assertEquals(5.548602818246955, body + 2.0 * title, EPS);
        assertTrue(body + 2.0 * title > 2.0 * 2.2);
    }
}
