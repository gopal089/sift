package dev.sift.engine.index;

import java.util.Arrays;

/**
 * The real words of the collection (not stemmed) with how many documents contain each one.
 * Used for spelling suggestions: "did you mean" must offer real words like "photosynthesis", while the
 * search index only knows stems like "photosynthesi".
 *
 * <p>Only plain a–z words that appear in at least {@link #MIN_DOCUMENTS} documents are kept, so that
 * misspellings inside Wikipedia itself do not become suggestions. Words are sorted, so a lookup is a
 * binary search.
 */
public final class Vocabulary {

    public static final int MIN_DOCUMENTS = 5;

    private final String[] words;        // sorted
    private final int[] documentCounts;  // documentCounts[i] = documents containing words[i]

    public Vocabulary(String[] sortedWords, int[] documentCounts) {
        this.words = sortedWords;
        this.documentCounts = documentCounts;
    }

    public int size() {
        return words.length;
    }

    public String word(int i) {
        return words[i];
    }

    public int documentCount(int i) {
        return documentCounts[i];
    }

    /** How many documents contain the word; 0 if it is not in the vocabulary. */
    public int documentCount(String word) {
        int i = Arrays.binarySearch(words, word);
        return i < 0 ? 0 : documentCounts[i];
    }

    public boolean contains(String word) {
        return Arrays.binarySearch(words, word) >= 0;
    }

    /** True for words we keep: only the letters a–z, at least 2 of them. */
    public static boolean isPlainWord(String word) {
        if (word.length() < 2) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }
}
