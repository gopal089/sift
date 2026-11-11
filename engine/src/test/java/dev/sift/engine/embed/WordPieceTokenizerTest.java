package dev.sift.engine.embed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class WordPieceTokenizerTest {

    // a toy vocabulary: id = position
    private static final List<String> TOY = List.of(
            "[PAD]", "[UNK]", "[CLS]", "[SEP]", "un", "##aff", "##able", "a", "##ff", "cafe", ",", "!", "##s", "dog");

    private final WordPieceTokenizer toy = new WordPieceTokenizer(TOY);

    @Test
    void greedyLongestMatchFirstWithContinuationPieces() {
        // "unaffable": "un" is the longest start piece; then "##aff" (longer than "##a…" options), then "##able"
        assertArrayEquals(new int[] {2, 4, 5, 6, 3}, toy.encode("unaffable", 16));
        assertArrayEquals(new int[] {2, 13, 12, 3}, toy.encode("Dogs", 16));
    }

    @Test
    void aWordThatCannotBeCutBecomesOneUnk() {
        // "unx": "un" matches, but no piece starts "##x" → the whole word is [UNK], not "un" + [UNK]
        assertArrayEquals(new int[] {2, 1, 3}, toy.encode("unx", 16));
    }

    @Test
    void accentsCaseAndPunctuation() {
        assertEquals(List.of("cafe", ",", "deja", "-", "vu", "!"), WordPieceTokenizer.basicTokens("Café, déjà-vu!"));
        assertArrayEquals(new int[] {2, 9, 10, 13, 11, 3}, toy.encode("CAFÉ,dog!", 16));
        // ASCII symbols are punctuation for BERT even though Unicode calls them math/currency symbols
        assertEquals(List.of("a", "+", "b", "$", "5"), WordPieceTokenizer.basicTokens("a+b$5"));
    }

    @Test
    void spacesControlCharactersAndChinese() {
        assertEquals(List.of("a", "b", "c"), WordPieceTokenizer.basicTokens("a b　c"));   // odd spaces split
        assertEquals(List.of("ab"), WordPieceTokenizer.basicTokens("a​b"));                   // zero-width: dropped
        assertEquals(List.of("ab"), WordPieceTokenizer.basicTokens("a\u0000\u0007b"));
        assertEquals(List.of("日", "本"), WordPieceTokenizer.basicTokens("日本"));                   // one word per character
        assertTrue(WordPieceTokenizer.basicTokens("").isEmpty());
    }

    @Test
    void longWordsAndTruncation() {
        assertArrayEquals(new int[] {2, 1, 3}, toy.encode("a".repeat(101), 16));   // over 100 characters
        int[] ids = toy.encode("dog ".repeat(50), 8);
        assertEquals(8, ids.length);
        assertEquals(2, ids[0]);
        assertEquals(3, ids[7]);   // [SEP] is kept at the end
        assertArrayEquals(new int[] {2, 3}, toy.encode("", 8));
    }

    @Test
    void helperClassesMatchBertRules() {
        assertTrue(WordPieceTokenizer.isWhitespace(0x00A0));
        assertTrue(WordPieceTokenizer.isControl(0x200B));
        assertFalse(WordPieceTokenizer.isControl('\n'));
        assertTrue(WordPieceTokenizer.isPunctuation('~'));
        assertTrue(WordPieceTokenizer.isPunctuation('«'));
        assertFalse(WordPieceTokenizer.isPunctuation('€'));
        assertTrue(WordPieceTokenizer.isChineseChar('東'));
        assertFalse(WordPieceTokenizer.isChineseChar('한'));
    }

    /**
     * The real check: the model's own tokenizer (Python) gave these ids for 1,000 strings with accents,
     * punctuation, numbers, emoji, other scripts and control characters. Every single id must match.
     */
    @Test
    void matchesTheModelTokenizerExactlyOnOneThousandStrings() throws IOException, URISyntaxException {
        Path vocab = Path.of(Objects.requireNonNull(getClass().getResource("/wordpiece/vocab.txt")).toURI());
        WordPieceTokenizer real = WordPieceTokenizer.load(vocab);
        assertEquals(30522, real.vocabSize());

        ObjectMapper json = new ObjectMapper();
        List<String> failures = new ArrayList<>();
        int cases = 0;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/wordpiece/cases.jsonl")), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                JsonNode node = json.readTree(line);
                String text = node.get("text").asText();
                int[] expected = new int[node.get("ids").size()];
                for (int i = 0; i < expected.length; i++) {
                    expected[i] = node.get("ids").get(i).asInt();
                }
                int[] actual = real.encode(text, 256);
                if (!Arrays.equals(expected, actual) && failures.size() < 10) {
                    failures.add(json.writeValueAsString(text) + "\n   expected " + pieces(real, expected)
                            + "\n   actual   " + pieces(real, actual));
                }
                cases++;
            }
        }
        assertEquals(1000, cases);
        assertTrue(failures.isEmpty(), "mismatches:\n" + String.join("\n", failures));
    }

    private static String pieces(WordPieceTokenizer t, int[] ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            sb.append(t.piece(id)).append(' ');
        }
        return sb.toString();
    }
}
