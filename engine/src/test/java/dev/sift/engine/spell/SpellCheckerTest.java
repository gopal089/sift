package dev.sift.engine.spell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sift.engine.index.Vocabulary;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class SpellCheckerTest {

    private static Vocabulary vocabulary(Object... wordsAndCounts) {
        TreeMap<String, Integer> sorted = new TreeMap<>();
        for (int i = 0; i < wordsAndCounts.length; i += 2) {
            sorted.put((String) wordsAndCounts[i], (Integer) wordsAndCounts[i + 1]);
        }
        return new Vocabulary(sorted.keySet().toArray(new String[0]),
                sorted.values().stream().mapToInt(Integer::intValue).toArray());
    }

    private static final Vocabulary WORDS = vocabulary("python", 197, "photon", 300, "einstein", 334, "history", 24273,
            "india", 9000, "tiger", 1246, "time", 50000, "tier", 40, "cat", 5000, "car", 9000, "cart", 300);

    @Test
    void bkTreeFindsExactlyTheWordsWithinTheDistance() {
        BkTree tree = new BkTree();
        List<String> words = List.of("book", "books", "cake", "boo", "cape", "cart", "boon", "cook", "back");
        for (String w : words) {
            tree.add(w);
        }
        assertEquals(words.size(), tree.size());
        assertEquals(0, tree.add("book"));   // adding again returns the old index and adds nothing
        assertEquals(words.size(), tree.size());
        List<String> found = new ArrayList<>();
        for (BkTree.Match m : tree.search("bok", 1)) {
            found.add(tree.word(m.wordIndex()) + "=" + m.distance());
        }
        found.sort(null);
        assertEquals(List.of("boo=1", "book=1"), found);
    }

    @Test
    void bkTreeGivesTheSameAnswerAsCheckingEveryWordButDoesLessWork() {
        Random random = new Random(7);
        List<String> words = new ArrayList<>();
        BkTree tree = new BkTree();
        for (int i = 0; i < 3000; i++) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0, len = 3 + random.nextInt(7); c < len; c++) {
                sb.append((char) ('a' + random.nextInt(6)));
            }
            if (tree.add(sb.toString()) == words.size()) {
                words.add(sb.toString());
            }
        }
        for (String query : List.of("abcabc", "fedcba", "aaaa", "bead", "cafebabe")) {
            for (int k = 0; k <= 2; k++) {
                List<String> expected = new ArrayList<>();
                for (String w : words) {
                    if (EditDistance.levenshtein(query, w) <= k) {
                        expected.add(w);
                    }
                }
                List<String> actual = new ArrayList<>();
                for (BkTree.Match m : tree.search(query, k)) {
                    actual.add(tree.word(m.wordIndex()));
                }
                expected.sort(null);
                actual.sort(null);
                assertEquals(expected, actual, query + " within " + k);
                if (k <= 1) {
                    assertTrue(tree.lastSearchDistanceCalls() < words.size(), "should skip part of the tree");
                }
            }
        }
    }

    @Test
    void aSwapBeatsAMoreFrequentWordThatIsTwoEditsAway() {
        // "pyhton": python is one swap away; photon is 2 edits away and in more documents
        assertEquals("python", new SpellChecker(WORDS).suggest("pyhton"));
        assertEquals("einstein", new SpellChecker(WORDS).suggest("einstien"));
    }

    @Test
    void closerWinsThenTheMoreFrequentWord() {
        SpellChecker s = new SpellChecker(WORDS);
        assertEquals("tiger", s.suggest("tigr"));    // tiger and tier are 1 away; "time" (very frequent) is 2 away
        assertEquals("car", s.suggest("caz"));       // cat and car are both 1 away: car is in more documents
    }

    @Test
    void knownWordsOddWordsAndFarWordsGetNoSuggestion() {
        SpellChecker s = new SpellChecker(WORDS);
        assertNull(s.suggest("python"));        // spelled correctly
        assertNull(s.suggest("zzzzzzzz"));      // nothing within 2 edits
        assertNull(s.suggest("ww2"));           // not a plain a-z word
        assertNull(s.suggest("x"));
        assertNull(s.suggest("cxzt"));          // short word: only 1 edit allowed, "cat"/"cart" are 2 away
    }

    @Test
    void correctsOnlyTheUnknownWordsOfAQuery() {
        SpellChecker s = new SpellChecker(WORDS);
        assertEquals("history of india", s.correctQuery("Histroy of India"));
        assertEquals("the history of python", s.correctQuery("the histroy of pyhton"));
        assertNull(s.correctQuery("history of india"));
        assertNull(s.correctQuery("who is the"));        // stop and question words are never "corrected"
        assertNull(s.correctQuery("🙂 !!!"));
        assertNull(s.correctQuery(""));
    }
}
