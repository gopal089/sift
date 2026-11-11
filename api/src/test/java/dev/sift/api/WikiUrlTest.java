package dev.sift.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WikiUrlTest {

    @Test
    void wikiUrlUsesUnderscoresAndPercentEncoding() {
        assertEquals("https://simple.wikipedia.org/wiki/World_War_II", SearchController.wikiUrl("World War II"));
        assertEquals("https://simple.wikipedia.org/wiki/AC%2FDC", SearchController.wikiUrl("AC/DC"));
        assertEquals("https://simple.wikipedia.org/wiki/Caf%C3%A9", SearchController.wikiUrl("Café"));
    }
}
