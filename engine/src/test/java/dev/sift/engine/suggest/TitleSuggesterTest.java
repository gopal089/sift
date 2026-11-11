package dev.sift.engine.suggest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sift.engine.index.IndexBuilder;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TitleSuggesterTest {

    private static TitleSuggester suggester;

    @BeforeAll
    static void build() {
        IndexBuilder b = new IndexBuilder();
        for (String title : List.of("Python (programming language)", "Pythagoras", "Pyramid", "python License",
                "Paris", "Zebra", "Pythagorean theorem", "Python Software Foundation", "Pytheas", "Pyrenees")) {
            b.addDocument(1, title, "text");
        }
        suggester = new TitleSuggester(b.build().docs());
    }

    @Test
    void prefixMatchesAlphabeticallyIgnoringCase() {
        assertEquals(List.of("Pythagoras", "Pythagorean theorem", "Pytheas", "Python (programming language)",
                "python License"), suggester.suggest("pyt", 5));
        assertEquals(List.of("Python (programming language)", "python License", "Python Software Foundation"),
                suggester.suggest("PYTHON", 5));
    }

    @Test
    void limitIsRespected() {
        assertEquals(2, suggester.suggest("p", 2).size());
    }

    @Test
    void noMatchEmptyAndEdgePrefixes() {
        assertEquals(List.of(), suggester.suggest("q", 5));
        assertEquals(List.of(), suggester.suggest("", 5));
        assertEquals(List.of(), suggester.suggest("   ", 5));
        assertEquals(List.of("Zebra"), suggester.suggest(" zeb ", 5));       // surrounding spaces ignored
        assertEquals(List.of(), suggester.suggest("zebras", 5));             // longer than any title
        assertEquals(List.of(), suggester.suggest("🙂", 5));
    }
}
