package dev.sift.engine.snippet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sift.engine.index.IndexBuilder;
import dev.sift.engine.index.InvertedIndex;
import dev.sift.engine.snippet.Snippet.Highlight;
import dev.sift.engine.text.Tokenizer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnippetBuilderTest {

    private static final String FILLER = "one two three four five six seven eight nine ten ";

    private static InvertedIndex index(String title, String text, List<String> aliases) {
        IndexBuilder b = new IndexBuilder();
        b.addDocument(1, title, text, aliases);
        b.addDocument(2, "Other", "Something else entirely about cats.", List.of());
        return b.build();
    }

    private static List<String> highlighted(Snippet s) {
        List<String> out = new ArrayList<>();
        for (Highlight h : s.highlights()) {
            out.add(s.text().substring(h.start(), h.end()));
        }
        return out;
    }

    @Test
    void shortArticleIsShownWholeWithQueryWordsMarked() {
        String text = "Plants make food. A plant needs sunlight!";
        Snippet s = SnippetBuilder.build(index("Plant", text, List.of()), 0, List.of("plant", "sunlight"), text, 30);
        assertEquals(text, s.text());                       // nothing cut: no "…", final "!" kept
        assertEquals(List.of("Plants", "plant", "sunlight"), highlighted(s));   // stemming: Plants = plant
    }

    @Test
    void windowMovesToWhereTheQueryWordsAre() {
        String text = FILLER.repeat(10) + "The volcano erupted and lava covered the town. " + FILLER.repeat(10);
        Snippet s = SnippetBuilder.build(index("Mountain", text, List.of()), 0, List.of("volcano", "lava"), text, 12);
        assertTrue(s.text().startsWith("… "), s.text());
        assertTrue(s.text().endsWith(" …"), s.text());
        assertEquals(List.of("volcano", "lava"), highlighted(s));
        // 5 lead words (seven eight nine ten The), then the match; 12 words in total
        assertEquals("… seven eight nine ten The volcano erupted and lava covered the town …", s.text());
    }

    @Test
    void windowWithMoreDifferentTermsBeatsAnEarlierWindowWithOne() {
        String text = "lava lava lava. " + FILLER.repeat(5) + "A volcano makes lava. " + FILLER.repeat(5);
        Snippet s = SnippetBuilder.build(index("X", text, List.of()), 0, List.of("volcano", "lava"), text, 8);
        assertEquals(List.of("volcano", "lava"), highlighted(s));
    }

    @Test
    void titleOnlyMatchFallsBackToTheStartOfTheArticle() {
        String text = FILLER.repeat(8);
        Snippet s = SnippetBuilder.build(index("Volcano", text, List.of()), 0, List.of("volcano"), text, 6);
        assertEquals("one two three four five six …", s.text());
        assertEquals(List.of(), s.highlights());
    }

    @Test
    void aliasWordsAfterTheBodyAreIgnored() {
        String text = "A country in North America.";
        Snippet s = SnippetBuilder.build(index("United States", text, List.of("USA")), 0, List.of("usa"), text, 30);
        assertEquals(text, s.text());
        assertEquals(List.of(), s.highlights());
    }

    @Test
    void accentsEmojiAndNewlinesKeepOffsetsRight() {
        String text = "🙂 Zürich\nis a café city. Ångström lived near Zürich 🙂.";
        Snippet s = SnippetBuilder.build(index("City", text, List.of()), 0, List.of("zurich", "cafe"), text, 30);
        assertEquals(text, s.text());
        assertEquals(List.of("Zürich", "café", "Zürich"), highlighted(s));
    }

    @Test
    void emptyTextAndUnknownTermsAreSafe() {
        assertEquals(new Snippet("", List.of()),
                SnippetBuilder.build(index("Empty", "", List.of()), 0, List.of("volcano"), "", 30));
        String text = "Hello world.";
        Snippet s = SnippetBuilder.build(index("Hi", text, List.of()), 0, List.of("zzzz"), text, 30);
        assertEquals(text, s.text());
        assertEquals(List.of(), s.highlights());
    }

    @Test
    void indexPositionIEqualsTheIthWordOfTheTokenizerWalk() {
        // The promise the snippet code relies on: position numbers in the index = word numbers in the text.
        String title = "Mount Etna";
        String text = "Etna is a volcano in Sicily. Etna's lava-flows reach the sea; a supercalifragilistic"
                + "expialidociousveryveryverylongtokenthatisskippedbecauseitisover64characters word.";
        InvertedIndex idx = index(title, text, List.of());
        int titleWords = idx.docs().titleLength(0);
        List<String> words = new ArrayList<>();
        Tokenizer.forEachWord(text, (word, start, end) -> words.add(Tokenizer.stemWord(word)));
        assertEquals(idx.docs().bodyLength(0), words.size());
        for (int i = 0; i < words.size(); i++) {
            int[] positions = idx.postings(words.get(i)).positions(0);
            int wanted = titleWords + i;
            assertTrue(java.util.Arrays.stream(positions).anyMatch(p -> p == wanted), words.get(i) + " at " + wanted);
        }
    }

    @Test
    void bestWindowPrefersDistinctTermsThenCountThenEarliest() {
        // (word, term): term 0 at 0, 1, 2; term 1 at 50; both at 100 and 103
        List<int[]> m = List.of(new int[] {0, 0}, new int[] {1, 0}, new int[] {2, 0}, new int[] {50, 1},
                new int[] {100, 0}, new int[] {103, 1});
        assertArrayEquals(new int[] {100, 103}, SnippetBuilder.bestWindow(m, 2, 10));   // 2 distinct beat 3 of one
        assertArrayEquals(new int[] {0, 2}, SnippetBuilder.bestWindow(m.subList(0, 4), 2, 10));   // 3 matches beat 1
        assertArrayEquals(new int[] {0, 0}, SnippetBuilder.bestWindow(List.of(), 2, 10));
        assertArrayEquals(new int[] {7, 7},
                SnippetBuilder.bestWindow(List.of(new int[] {7, 0}, new int[] {90, 0}), 1, 10));
    }

    @Test
    void leadWordsNeverPushTheLastMatchOutOfTheWindow() {
        // "lava" is word 20 and "volcano" word 27: an 8-word window has no room for lead words
        String text = FILLER.repeat(2) + "lava a b c d e f volcano. " + FILLER.repeat(3);
        Snippet s = SnippetBuilder.build(index("X", text, List.of()), 0, List.of("volcano", "lava"), text, 8);
        assertEquals("… lava a b c d e f volcano …", s.text());
        assertEquals(List.of("lava", "volcano"), highlighted(s));
    }
}
