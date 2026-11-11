package dev.sift.engine.rank;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sift.engine.data.RedirectAliases;
import dev.sift.engine.data.RedirectAliases.Redirect;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageRankTest {

    private static double sum(double[] values) {
        double s = 0;
        for (double v : values) {
            s += v;
        }
        return s;
    }

    /**
     * Worked by hand (also in docs/learning/phase-4.md). 4 pages, d = 0.85, start 0.25 each:
     * 0 → 1, 2;  1 → 2;  2 → 0;  3 → 2.
     * <pre>
     * round 1:  base = 0.15 / 4 = 0.0375
     *   page 0 = 0.0375 + 0.85 × 0.25           (from 2)              = 0.25
     *   page 1 = 0.0375 + 0.85 × 0.25 / 2       (half of 0)           = 0.14375
     *   page 2 = 0.0375 + 0.85 × (0.125 + 0.25 + 0.25)  (0, 1, 3)    = 0.56875
     *   page 3 = 0.0375                         (nobody links to it)  = 0.0375
     * </pre>
     */
    @Test
    void oneAndTwoRoundsWorkedByHand() {
        LinkGraph g = LinkGraph.of(List.of(new int[] {1, 2}, new int[] {2}, new int[] {0}, new int[] {2}));
        PageRank.Result one = PageRank.compute(g, 0.85, 0, 1);
        assertArrayEquals(new double[] {0.25, 0.14375, 0.56875, 0.0375}, one.ranks(), 1e-12);
        assertEquals(1, one.rounds());
        PageRank.Result two = PageRank.compute(g, 0.85, 0, 2);
        assertArrayEquals(new double[] {0.5209375, 0.14375, 0.2978125, 0.0375}, two.ranks(), 1e-12);
    }

    @Test
    void danglingPageSharesItsRankWithEveryone() {
        // 0 → 1, and 1 links to nothing. Round 1: both get 0.075 + 0.85 × 0.5 / 2; page 1 also gets 0.85 × 0.5.
        LinkGraph g = LinkGraph.of(List.of(new int[] {1}, new int[] {}));
        assertEquals(1, g.danglingCount());
        PageRank.Result r = PageRank.compute(g, 0.85, 0, 1);
        assertArrayEquals(new double[] {0.2875, 0.7125}, r.ranks(), 1e-12);
    }

    @Test
    void ranksAlwaysAddUpToOneAndConverge() {
        LinkGraph g = LinkGraph.of(List.of(new int[] {1, 2}, new int[] {2}, new int[] {0}, new int[] {2},
                new int[] {}, new int[] {0, 4}));
        PageRank.Result r = PageRank.compute(g, 0.85, 1e-6, 50);
        assertEquals(1.0, sum(r.ranks()), 1e-9);
        assertTrue(r.change() < 1e-6, "change " + r.change());
        assertTrue(r.rounds() < 50);
        // page 2 has the most links pointing to it; page 5 has none
        assertTrue(r.ranks()[2] > r.ranks()[1]);
        assertTrue(r.ranks()[5] < r.ranks()[4]);
    }

    @Test
    void stopsAtTheRoundLimit() {
        LinkGraph g = LinkGraph.of(List.of(new int[] {1}, new int[] {2}, new int[] {0}, new int[] {0}));
        assertEquals(3, PageRank.compute(g, 0.85, 0, 3).rounds());
    }

    @Test
    void symmetricRingGivesEveryPageTheSameRank() {
        LinkGraph g = LinkGraph.of(List.of(new int[] {1}, new int[] {2}, new int[] {0}));
        PageRank.Result r = PageRank.compute(g, 0.85, 1e-9, 50);
        assertArrayEquals(new double[] {1 / 3.0, 1 / 3.0, 1 / 3.0}, r.ranks(), 1e-12);
        assertEquals(1, r.rounds());   // already stable after the first round
    }

    @Test
    void graphDropsDuplicateAndSelfLinks() {
        LinkGraph g = LinkGraph.of(List.of(new int[] {1, 1, 0, 2, 1}, new int[] {1}, new int[] {}));
        assertEquals(2, g.outDegree(0));
        assertEquals(1, g.target(0, 0));
        assertEquals(2, g.target(0, 1));
        assertEquals(0, g.outDegree(1));
        assertEquals(2, g.linkCount());
        assertEquals(2, g.danglingCount());
    }

    @Test
    void normalisedLogIsZeroForTheSmallestAndOneForTheLargest() {
        double[] boost = PageRank.normalisedLog(new double[] {0.001, 0.01, 0.1});
        assertArrayEquals(new double[] {0.0, 0.5, 1.0}, boost, 1e-12);   // each step is ×10: evenly spaced on a log scale
        assertArrayEquals(new double[] {0, 0}, PageRank.normalisedLog(new double[] {0.5, 0.5}), 0.0);
        assertArrayEquals(new double[0], PageRank.normalisedLog(new double[0]), 0.0);
    }

    @Test
    void graphFromArticlesFollowsRedirectsAndDropsUnknownTargets(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("articles.jsonl");
        Files.writeString(file, """
                {"id": 1, "title": "United States", "text": "x", "links": ["Canada", "No such page"]}
                {"id": 2, "title": "Canada", "text": "x", "links": ["USA", "Canada", "United States"]}
                {"id": 3, "title": "Mexico", "text": "x"}
                """);
        RedirectAliases redirects = RedirectAliases.of(List.of(new Redirect("USA", "United States")));
        LinkGraph g = LinkGraph.fromArticles(file, redirects);
        assertEquals(3, g.pageCount());
        assertEquals(1, g.outDegree(0));          // "No such page" dropped
        assertEquals(1, g.target(0, 0));
        assertEquals(1, g.outDegree(1));          // USA and United States are the same page; the self link is dropped
        assertEquals(0, g.target(1, 0));
        assertEquals(0, g.outDegree(2));          // no "links" field at all
    }
}
