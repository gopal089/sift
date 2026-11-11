package dev.sift.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SnippetTest {

    @Test
    void shortTextIsKeptAndWhitespaceCollapsed() {
        assertEquals("A plant is green.", SearchController.shorten("  A plant\n\n is   green. ", 200));
        assertEquals("", SearchController.shorten("", 200));
    }

    @Test
    void longTextIsCutAtAWordBoundaryWithEllipsis() {
        assertEquals("one two three…", SearchController.shorten("one two three four five", 16));
    }

    @Test
    void halfCharacterFromAByteCutIsRemoved() {
        assertEquals("caf", SearchController.shorten("caf�", 200));
    }

    @Test
    void emojiCountAsOneCharacterAndAreNeverSplit() {
        assertEquals("🙂🙂…", SearchController.shorten("🙂🙂🙂🙂", 2));
    }

    @Test
    void wikiUrlUsesUnderscoresAndPercentEncoding() {
        assertEquals("https://simple.wikipedia.org/wiki/World_War_II", SearchController.wikiUrl("World War II"));
        assertEquals("https://simple.wikipedia.org/wiki/AC%2FDC", SearchController.wikiUrl("AC/DC"));
        assertEquals("https://simple.wikipedia.org/wiki/Caf%C3%A9", SearchController.wikiUrl("Café"));
    }
}
