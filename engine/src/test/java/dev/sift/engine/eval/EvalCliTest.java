package dev.sift.engine.eval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sift.engine.search.Ranking;
import dev.sift.engine.search.SearchResult.MatchMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvalCliTest {

    @Test
    void parsesConfigAndKnowsIfItNeedsAliases() {
        EvalCli.Config a = EvalCli.parseConfig("w2=bm25f:w=2,bb=0.5");
        assertEquals("w2", a.name());
        assertEquals(Ranking.parse("bm25f:w=2,bb=0.5"), a.ranking());
        assertFalse(a.usesAliases());

        assertTrue(EvalCli.parseConfig("s2a=bm25sum:w=2,bb=0.3,wa=1").usesAliases());
        assertEquals(Ranking.BASELINE, EvalCli.parseConfig("base=bm25").ranking());
    }

    @Test
    void rejectsBadConfigs() {
        assertThrows(IllegalArgumentException.class, () -> EvalCli.parseConfig("bm25f:w=2"));
        assertThrows(IllegalArgumentException.class, () -> EvalCli.parseConfig("x=bm25f:aliases=some"));
    }

    @Test
    void betterWorseSameTreatsNotFoundAsWorst() {
        // reference ranks: 5, 1, not found, 3 ; other: 2, 1, 40, not found
        List<Evaluator.Outcome> ref = outcomes(5, 1, 0, 3);
        List<Evaluator.Outcome> other = outcomes(2, 1, 40, 0);
        // better: 5→2, -→40 ; worse: 3→- ; same: 1→1
        assertArrayEquals(new int[] {2, 1, 1}, EvalReport.betterWorseSame(ref, other));
    }

    private static List<Evaluator.Outcome> outcomes(int... ranks) {
        List<Evaluator.Outcome> out = new ArrayList<>();
        for (int i = 0; i < ranks.length; i++) {
            EvalQuery q = new EvalQuery(i + 1, "q" + i, "T" + i, "c", "");
            out.add(new Evaluator.Outcome(q, List.of(), MatchMode.AND, 1, ranks[i], ""));
        }
        return out;
    }
}
