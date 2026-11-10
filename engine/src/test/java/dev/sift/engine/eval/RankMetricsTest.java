package dev.sift.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class RankMetricsTest {

    private static final double EPS = 1e-9;

    @Test
    void reciprocalRankOfTopFirstSecondAndMissing() {
        assertEquals(1.0, RankMetrics.reciprocalRank(1), EPS);
        assertEquals(0.5, RankMetrics.reciprocalRank(2), EPS);
        assertEquals(0.1, RankMetrics.reciprocalRank(10), EPS);
        assertEquals(0.0, RankMetrics.reciprocalRank(RankMetrics.NOT_FOUND), EPS);
    }

    @Test
    void summaryWorkedByHand() {
        // ranks 1, 2, not found, 10, 11
        // MRR = (1 + 1/2 + 0 + 1/10 + 1/11) / 5 = 1.690909... / 5 = 0.338181...
        RankMetrics.Summary s = RankMetrics.summarize(List.of(1, 2, 0, 10, 11));
        assertEquals(5, s.queries());
        assertEquals((1 + 0.5 + 0 + 0.1 + 1.0 / 11) / 5, s.mrr(), EPS);
        assertEquals(1 / 5.0, s.hitAt1(), EPS);   // only rank 1
        assertEquals(2 / 5.0, s.hitAt3(), EPS);   // ranks 1, 2
        assertEquals(3 / 5.0, s.hitAt10(), EPS);  // ranks 1, 2, 10 (11 is just outside)
        assertEquals(1, s.notFound());
    }

    @Test
    void perfectAndHopeless() {
        assertEquals(1.0, RankMetrics.summarize(List.of(1, 1, 1)).mrr(), EPS);
        RankMetrics.Summary none = RankMetrics.summarize(List.of(0, 0));
        assertEquals(0.0, none.mrr(), EPS);
        assertEquals(0.0, none.hitAt10(), EPS);
        assertEquals(2, none.notFound());
    }

    @Test
    void rejectsEmptyAndNegative() {
        assertThrows(IllegalArgumentException.class, () -> RankMetrics.summarize(List.of()));
        assertThrows(IllegalArgumentException.class, () -> RankMetrics.reciprocalRank(-1));
    }
}
