package dev.sift.engine.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TokenizerTest {

    // ---------------------------------------------------------------- index time

    @Test
    void lowercasesSplitsAndStems() {
        assertEquals(List.of("plant", "make", "food", "by", "photosynthesi"),
                Tokenizer.tokenize("Plants make food by Photosynthesis!"));
    }

    @Test
    void keepsStopWordsAtIndexTime() {
        assertEquals(List.of("the", "histori", "of", "india"), Tokenizer.tokenize("The history of India"));
        assertEquals(List.of("to", "be", "or", "not", "to", "be"), Tokenizer.tokenize("To be, or not to be"));
    }

    @Test
    void splitsOnPunctuationAndKeepsDigits() {
        assertEquals(List.of("hello", "world", "it", "s", "2024"), Tokenizer.tokenize("Hello, World! It's 2024."));
        assertEquals(List.of("world", "war", "2", "mp3", "player", "3", "14"),
                Tokenizer.tokenize("World War 2, MP3-player 3.14"));
    }

    @Test
    void foldsAccents() {
        assertEquals(List.of("cafe", "angstrom", "naiv"), Tokenizer.tokenize("Café Ångström naïve"));
        // the same word written with a separate combining accent (e + U+0301) gives the same term
        assertEquals(Tokenizer.tokenize("café"), Tokenizer.tokenize("café"));
    }

    @Test
    void keepsNonLatinWordsUnstemmed() {
        assertEquals(List.of("東京", "москва", "παρισι"), Tokenizer.tokenize("東京 Москва Παρίσι"));
    }

    @Test
    void emojiAndSymbolsAreSeparators() {
        assertEquals(List.of("i", "pizza", "is", "great"), Tokenizer.tokenize("I ❤️ pizza🍕is great"));
    }

    @Test
    void skipsVeryLongTokens() {
        String junk = "x".repeat(Tokenizer.MAX_TOKEN_LENGTH + 1);
        assertEquals(List.of("befor", "after"), Tokenizer.tokenize("before " + junk + " after")); // Porter: before → befor
        String ok = "y".repeat(Tokenizer.MAX_TOKEN_LENGTH);
        assertEquals(1, Tokenizer.tokenize(ok).size());
    }

    @Test
    void emptyInputs() {
        assertEquals(List.of(), Tokenizer.tokenize(""));
        assertEquals(List.of(), Tokenizer.tokenize(null));
        assertEquals(List.of(), Tokenizer.tokenize("!!! ... ---"));
    }

    // ---------------------------------------------------------------- query time

    @Test
    void queryDropsStopWordsWhenAContentWordRemains() {
        assertEquals(List.of("histori", "india"), Tokenizer.queryTerms("the history of india"));
    }

    @Test
    void queryKeepsAllWordsWhenAllAreStopWords() {
        // duplicates removed: to, be appear twice
        assertEquals(List.of("to", "be", "or", "not"), Tokenizer.queryTerms("to be or not to be"));
    }

    @Test
    void theWhoKeepsOnlyWhoBecauseWhoIsNotAStopWord() {
        // "who" is not in the classic 33-word list, so "the" is dropped and "who" is searched.
        assertEquals(List.of("who"), Tokenizer.queryTerms("the who"));
        // a query made only of stop words keeps them all
        assertEquals(List.of("the", "it"), Tokenizer.queryTerms("The It"));
    }

    @Test
    void queryRemovesDuplicatesAfterStemming() {
        assertEquals(List.of("dog"), Tokenizer.queryTerms("dog Dogs DOG"));
    }

    @Test
    void queryAndIndexAgreeOnTerms() {
        String text = "Photosynthesis in green plants";
        assertEquals(List.of("photosynthesi", "green", "plant"), Tokenizer.queryTerms(text));
        assertEquals(List.of("photosynthesi", "in", "green", "plant"), Tokenizer.tokenize(text));
    }

    @Test
    void emptyQuery() {
        assertEquals(List.of(), Tokenizer.queryTerms("   "));
        assertEquals(List.of(), Tokenizer.queryTerms("🙂🙂"));
    }
}
