package dev.sift.engine.text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Turns text into index terms. This is the ONE tokenizer used by both indexing and querying,
 * so a word is always turned into the same term on both sides.
 *
 * <p>Steps for every word:
 * <ol>
 *   <li>Split on anything that is not a letter or digit (spaces, punctuation, emoji).</li>
 *   <li>Lowercase, and fold accents: "Café" → "cafe".</li>
 *   <li>Skip absurdly long tokens (&gt; {@value #MAX_TOKEN_LENGTH} chars), which are junk such as encoded data.</li>
 *   <li>Stem with the Porter stemmer, but only plain a-z words (Porter is an English algorithm).</li>
 * </ol>
 *
 * <p>Example: {@code "Plants make food by Photosynthesis!"} →
 * {@code [plant, make, food, by, photosynthesi]}.
 */
public final class Tokenizer {

    public static final int MAX_TOKEN_LENGTH = 64;

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private Tokenizer() {
    }

    /**
     * Index-time tokenization: every term, in order, stop words included.
     * The position of a term is its index in the returned list.
     */
    public static List<String> tokenize(String text) {
        List<String> terms = new ArrayList<>();
        forEachWord(text, word -> terms.add(stem(word)));
        return terms;
    }

    /**
     * Query-time tokenization.
     *
     * <ul>
     *   <li>Stop words are dropped only if at least one non-stop word remains:
     *       "the history of india" → [histori, india],
     *       but "to be or not to be" (all stop words) → [to, be, or, not].</li>
     *   <li>Duplicates are removed, keeping the first-seen order: "dog dogs" → [dog].</li>
     * </ul>
     */
    public static List<String> queryTerms(String query) {
        List<String> words = new ArrayList<>();
        forEachWord(query, words::add);

        boolean hasContentWord = words.stream().anyMatch(w -> !StopWords.isStopWord(w));
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String word : words) {
            if (hasContentWord && StopWords.isStopWord(word)) {
                continue;
            }
            terms.add(stem(word));
        }
        return List.copyOf(terms);
    }

    /** Calls {@code sink} with every normalised (lowercase, accent-folded) word, before stemming. */
    private static void forEachWord(String text, Consumer<String> sink) {
        if (text == null || text.isEmpty()) {
            return;
        }
        StringBuilder word = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);   // code points, so emoji / rare scripts are handled correctly
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                word.appendCodePoint(cp);
            } else if (!word.isEmpty()) {
                emit(word, sink);
            }
        }
        if (!word.isEmpty()) {
            emit(word, sink);
        }
    }

    private static void emit(StringBuilder word, Consumer<String> sink) {
        if (word.length() <= MAX_TOKEN_LENGTH) {
            String w = word.toString().toLowerCase(Locale.ROOT);
            sink.accept(isAscii(w) ? w : foldAccents(w));
        }
        word.setLength(0);
    }

    /** "café" → "cafe", "Ångström" → "angstrom". Splits letters from their accents, then drops the accents. */
    static String foldAccents(String word) {
        return COMBINING_MARKS.matcher(Normalizer.normalize(word, Normalizer.Form.NFD)).replaceAll("");
    }

    private static String stem(String word) {
        return isLowercaseLatin(word) ? PorterStemmer.stem(word) : word;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
    }

    private static boolean isLowercaseLatin(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 'a' || c > 'z') return false;
        }
        return true;
    }
}
