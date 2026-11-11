package dev.sift.engine.suggest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sift.engine.index.IndexBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class TitleTrieTest {

    private static TitleTrie trie(List<String> titles, double[] pageRank) {
        IndexBuilder b = new IndexBuilder();
        for (String title : titles) {
            b.addDocument(1, title, "text");
        }
        b.setPageRank(pageRank);
        return new TitleTrie(b.build().docs());
    }

    private static final List<String> TITLES = List.of("Pythagoras", "Python (programming language)", "Pyramid",
            "python License", "Paris", "Zebra", "Pythagorean theorem", "Python", "Pytheas", "Pyrenees");
    //                                         Pythagoras Python(pl) Pyramid License Paris Zebra theorem Python Pytheas Pyrenees
    private static final double[] RANK = {0.10, 0.20, 0.05, 0.01, 0.30, 0.02, 0.03, 0.25, 0.005, 0.035};

    @Test
    void completionsAreOrderedByPageRankNotAlphabet() {
        TitleTrie t = trie(TITLES, RANK);
        assertEquals(List.of("Python", "Python (programming language)", "Pythagoras", "Pythagorean theorem",
                "python License"), t.suggest("pyt", 5));       // Pytheas (lowest rank) does not fit in the top 5
        assertEquals(List.of("Paris", "Python", "Python (programming language)", "Pythagoras", "Pyramid"),
                t.suggest("p", 5));
    }

    @Test
    void caseSpacesAndLimit() {
        TitleTrie t = trie(TITLES, RANK);
        assertEquals(List.of("Python", "Python (programming language)", "python License"), t.suggest("  PYTHON ", 5));
        assertEquals(List.of("Paris", "Python"), t.suggest("p", 2));
        assertEquals(List.of("Python (programming language)"), t.suggest("python (", 5));
    }

    @Test
    void noMatchEmptyAndOddPrefixes() {
        TitleTrie t = trie(TITLES, RANK);
        assertEquals(List.of(), t.suggest("q", 5));
        assertEquals(List.of(), t.suggest("", 5));
        assertEquals(List.of(), t.suggest("   ", 5));
        assertEquals(List.of(), t.suggest("zebras", 5));
        assertEquals(List.of(), t.suggest("🙂", 5));
    }

    @Test
    void withoutPageRankTheOrderIsAlphabetical() {
        TitleTrie t = trie(List.of("Cb", "Ca", "Cc"), null);
        assertEquals(List.of("Ca", "Cb", "Cc"), t.suggest("c", 5));
    }

    @Test
    void prefixesLongerThanTheTreeDepthAreCheckedAgainstTheTitle() {
        String shared = "A very long title that goes on";               // 30 characters, deeper than the tree
        TitleTrie t = trie(List.of(shared + " and ends here", shared + " but ends there"), new double[] {0.4, 0.6});
        assertEquals(List.of(shared + " but ends there", shared + " and ends here"), t.suggest("a very long", 5));
        assertEquals(List.of(shared + " and ends here"), t.suggest(shared + " and", 5));
        assertEquals(List.of(), t.suggest(shared + " or", 5));
    }

    @Test
    void manyTitlesGrowTheArraysAndKeepTheTopFive() {
        List<String> titles = new java.util.ArrayList<>();
        double[] rank = new double[3000];
        for (int i = 0; i < 3000; i++) {
            titles.add("Item " + i);
            rank[i] = i + 1;   // "Item 2999" is the most important
        }
        TitleTrie t = trie(titles, rank);
        assertEquals(List.of("Item 2999", "Item 2998", "Item 2997", "Item 2996", "Item 2995"), t.suggest("item", 5));
        assertEquals(List.of("Item 1999", "Item 1998", "Item 1997", "Item 1996", "Item 1995"), t.suggest("item 19", 5));
        assertTrue(t.nodeCount() > 3000);
    }
}
