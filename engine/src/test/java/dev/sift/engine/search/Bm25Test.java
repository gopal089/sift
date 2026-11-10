package dev.sift.engine.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Bm25Test {

    // Tiny corpus: 3 documents with lengths 7, 6, 3 → average length 16/3.
    private final Bm25 bm25 = new Bm25(3, 16.0 / 3.0);

    /**
     * Worked by hand (and cross-checked in Python):
     *   idf(n=2)    = ln((3 − 2 + 0.5) / (2 + 0.5) + 1) = ln(1.6)          = 0.470004
     *   lengthNorm  = 1.2 × (1 − 0.75 + 0.75 × 7 / (16/3)) = 1.2 × 1.234375 = 1.48125
     *   score       = 0.470004 × 2 × 2.2 / (2 + 1.48125)                     = 0.594044
     */
    @Test
    void matchesHandWorkedExample() {
        double idf = bm25.idf(2);
        assertEquals(Math.log(1.6), idf, 1e-12);
        assertEquals(0.594044084360858, bm25.termScore(idf, 2, 7), 1e-12);
        assertEquals(0.44713858782297017, bm25.termScore(idf, 1, 6), 1e-12);
    }

    @Test
    void rarerTermsHaveHigherIdfAndIdfIsNeverNegative() {
        assertEquals(0.9808292530117263, bm25.idf(1), 1e-12);
        assertTrue(bm25.idf(1) > bm25.idf(2));
        assertTrue(bm25.idf(3) > 0, "a term in every document still gets a small positive idf");
    }

    @Test
    void moreMentionsHelpButSaturate() {
        double idf = bm25.idf(1);
        double one = bm25.termScore(idf, 1, 5);
        double two = bm25.termScore(idf, 2, 5);
        double hundred = bm25.termScore(idf, 100, 5);
        assertTrue(two > one);
        assertTrue(two < 2 * one, "the 2nd mention adds less than the 1st");
        assertTrue(hundred < idf * (Bm25.DEFAULT_K1 + 1), "score can never exceed idf × (k1 + 1)");
    }

    @Test
    void longerDocumentsScoreLowerForTheSameTf() {
        double idf = bm25.idf(1);
        assertTrue(bm25.termScore(idf, 1, 3) > bm25.termScore(idf, 1, 30));
    }

    @Test
    void bZeroTurnsOffLengthNormalisation() {
        Bm25 noLength = new Bm25(3, 16.0 / 3.0, 1.2, 0.0);
        double idf = noLength.idf(1);
        assertEquals(noLength.termScore(idf, 1, 3), noLength.termScore(idf, 1, 3000), 1e-12);
    }

    @Test
    void rejectsEmptyCorpus() {
        assertThrows(IllegalArgumentException.class, () -> new Bm25(0, 1.0));
    }
}
