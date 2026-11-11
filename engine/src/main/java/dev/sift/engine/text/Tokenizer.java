package dev.sift.engine.text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
        forEachWord(text, (word, start, end) -> {
            terms.add(stem(word));
            return true;
        });
        return terms;
    }

    /** Receives one word: normalised (lowercase, accents folded, not stemmed) and where it is in the text. */
    public interface WordSink {
        /**
         * @param start index of the word's first character in the text
         * @param end   index just after its last character
         * @return false to stop reading the rest of the text
         */
        boolean word(String word, int start, int end);
    }

    /**
     * Walks the words of a text in exactly the order {@link #tokenize} does, so the i-th word seen here
     * is the term at position i in the index. Used to turn word positions back into character offsets
     * (snippets). Call {@link #stemWord} on a word to get its index term.
     */
    public static void forEachWord(String text, WordSink sink) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int start = -1;   // where the current word began, or -1 if we are between words
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);   // code points, so emoji / rare scripts are handled correctly
            if (Character.isLetterOrDigit(cp)) {
                if (start < 0) {
                    start = i;
                }
            } else if (start >= 0) {
                if (!emit(text, start, i, sink)) {
                    return;
                }
                start = -1;
            }
            i += Character.charCount(cp);
        }
        if (start >= 0) {
            emit(text, start, text.length(), sink);
        }
    }

    /** The index term of a normalised word (Porter stem for plain a-z words). */
    public static String stemWord(String word) {
        return stem(word);
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
        return queryTerms(query, false);
    }

    /**
     * Query-time tokenization where question words (who, what, when, ...) can also count as stop words:
     * "who invented the telephone" → [invent, telephon]. The "keep everything if only stop words" rule
     * still applies, so "the who" → [the, who].
     */
    public static List<String> queryTerms(String query, boolean dropQuestionWords) {
        List<String> words = new ArrayList<>();
        forEachWord(query, (word, start, end) -> words.add(word));

        boolean hasContentWord = words.stream().anyMatch(w -> !StopWords.isStopWord(w, dropQuestionWords));
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String word : words) {
            if (hasContentWord && StopWords.isStopWord(word, dropQuestionWords)) {
                continue;
            }
            terms.add(stem(word));
        }
        return List.copyOf(terms);
    }

    /** Normalises text[start, end) and hands it to the sink. Absurdly long tokens are skipped. */
    private static boolean emit(String text, int start, int end, WordSink sink) {
        if (end - start > MAX_TOKEN_LENGTH) {
            return true;
        }
        String w = text.substring(start, end).toLowerCase(Locale.ROOT);
        return sink.word(isAscii(w) ? w : foldAccents(w), start, end);
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
