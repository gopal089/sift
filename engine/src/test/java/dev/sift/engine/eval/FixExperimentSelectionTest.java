package dev.sift.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sift.engine.eval.FixExperimentCli.Candidate;
import dev.sift.engine.eval.FixExperimentCli.Variant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pre-registered selection rule: best MRR, ties within 0.01 broken by one vector > MiniLM > plain RRF. */
class FixExperimentSelectionTest {

    private static final Variant A = FixExperimentCli.VARIANTS.get(0);   // MiniLM, one vector
    private static final Variant C = FixExperimentCli.VARIANTS.get(2);   // MiniLM, two vectors
    private static final Variant D = FixExperimentCli.VARIANTS.get(3);   // bge, one vector

    private static String pick(Candidate... cs) {
        return FixExperimentCli.select(List.of(cs), new ArrayList<>()).name();
    }

    @Test
    void clearWinnerTakesIt() {
        // 0.90 vs 0.88: more than 0.01 apart, so the simpler config does not matter
        assertEquals("D-rrf", pick(new Candidate("A-rrf", A, 1, 0.88), new Candidate("D-rrf", D, 1, 0.90)));
    }

    @Test
    void withinTheMarginOneVectorBeatsTwo() {
        assertEquals("A-rrf", pick(new Candidate("C-rrf", C, 1, 0.900), new Candidate("A-rrf", A, 1, 0.891)));
    }

    @Test
    void withinTheMarginMiniLmBeatsBge() {
        assertEquals("A-rrf-w2", pick(new Candidate("D-rrf", D, 1, 0.900), new Candidate("A-rrf-w2", A, 2, 0.895)));
    }

    @Test
    void withinTheMarginPlainBeatsWeighted() {
        assertEquals("A-rrf", pick(new Candidate("A-rrf-w2", A, 2, 0.900), new Candidate("A-rrf", A, 1, 0.890)));
    }

    @Test
    void exactlyOneHundredthIsStillATie() {
        assertEquals("A-rrf", pick(new Candidate("C-rrf", C, 1, 0.87), new Candidate("A-rrf", A, 1, 0.86)));
    }

    @Test
    void tiedListIsInTieBreakOrderAndExcludesConfigsOutsideTheMargin() {
        List<Candidate> tied = new ArrayList<>();
        FixExperimentCli.select(List.of(new Candidate("D-rrf", D, 1, 0.905), new Candidate("C-rrf", C, 1, 0.900),
                new Candidate("A-rrf-w2", A, 2, 0.899), new Candidate("A-rrf", A, 1, 0.880)), tied);
        assertEquals(List.of("A-rrf-w2", "D-rrf", "C-rrf"), tied.stream().map(Candidate::name).toList());
    }
}
