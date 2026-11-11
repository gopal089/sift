package dev.sift.engine.spell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EditDistanceTest {

    @ParameterizedTest
    @CsvSource({
            "cat, cat, 0",
            "cut, cat, 1",            // replace
            "cat, cats, 1",           // insert
            "cats, cat, 1",           // delete
            "kitten, sitting, 3",     // the classic example
            "'', abc, 3",
            "abc, '', 3",
            "'', '', 0",
            "pyhton, python, 2",      // a swap costs 2 in plain Levenshtein
            "einstien, einstein, 2",
            "photosyntesis, photosynthesis, 1",
            "elefant, elephant, 2",
            "book, back, 2",
    })
    void levenshtein(String a, String b, int expected) {
        assertEquals(expected, EditDistance.levenshtein(a, b));
        assertEquals(expected, EditDistance.levenshtein(b, a));   // symmetric
    }

    @ParameterizedTest
    @CsvSource({
            "pyhton, python, 1",      // one swap
            "einstien, einstein, 1",
            "ab, ba, 1",
            "abc, cab, 2",            // not a neighbour swap
            "kitten, sitting, 3",     // no swaps: same as Levenshtein
            "cat, cat, 0",
            "'', ab, 2",
            "mountian, mountain, 1",
            "pyhton, photon, 2",
    })
    void withSwaps(String a, String b, int expected) {
        assertEquals(expected, EditDistance.withSwaps(a, b));
        assertEquals(expected, EditDistance.withSwaps(b, a));
    }
}
