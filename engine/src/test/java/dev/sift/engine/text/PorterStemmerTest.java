package dev.sift.engine.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PorterStemmerTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            // Step 1a: plurals
            "caresses, caress", "ponies, poni", "caress, caress", "cats, cat",
            // Step 1b: -eed, -ed, -ing and the clean-up rules
            "feed, feed", "agreed, agre", "plastered, plaster", "bled, bled", "motoring, motor", "sing, sing",
            "conflated, conflat", "troubled, troubl", "sized, size", "hopping, hop", "tanned, tan",
            "falling, fall", "hissing, hiss", "fizzed, fizz", "failing, fail", "filing, file",
            // Step 1c: y -> i
            "happy, happi", "sky, sky",
            // Step 2
            "relational, relat", "conditional, condit", "digitizer, digit", "operator, oper",
            // Step 3
            "triplicate, triplic", "hopeful, hope", "goodness, good",
            // Step 4
            "revival, reviv", "adjustment, adjust", "adoption, adopt", "lion, lion",
            // Step 5
            "probate, probat", "rate, rate", "controlling, control", "roll, roll",
            // Family of words that should share one stem
            "connect, connect", "connected, connect", "connecting, connect", "connection, connect",
            "connections, connect",
    })
    void stemsClassicExamples(String word, String expected) {
        assertEquals(expected, PorterStemmer.stem(word));
    }

    @Test
    void shortWordsAreUnchanged() {
        assertEquals("a", PorterStemmer.stem("a"));
        assertEquals("is", PorterStemmer.stem("is"));
        assertEquals("as", PorterStemmer.stem("as"));
    }

    /** The strongest test: Martin Porter's official list of 23,531 words and their expected stems. */
    @Test
    void matchesOfficialReferenceVocabulary() throws IOException {
        List<String> words = readLines("/porter/voc.txt");
        List<String> expected = readLines("/porter/output.txt");
        assertEquals(words.size(), expected.size());
        assertEquals(23_531, words.size());

        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            String actual = PorterStemmer.stem(words.get(i));
            if (!actual.equals(expected.get(i))) {
                mismatches.add(words.get(i) + " -> " + actual + " (expected " + expected.get(i) + ")");
            }
        }
        assertEquals(List.of(), mismatches.subList(0, Math.min(20, mismatches.size())),
                mismatches.size() + " mismatches; first 20 shown");
    }

    private static List<String> readLines(String resource) throws IOException {
        try (InputStream in = PorterStemmerTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing test resource " + resource);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return reader.lines().map(String::strip).toList();
        }
    }
}
