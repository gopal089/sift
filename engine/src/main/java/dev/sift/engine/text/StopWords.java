package dev.sift.engine.text;

import java.util.Set;

/**
 * Very common English words that carry little meaning on their own.
 *
 * <p>This is the classic 33-word English list used by Lucene. We keep the list small on
 * purpose: BM25's IDF already gives common words a tiny weight, and a big list would make
 * queries like "to be or not to be" impossible.
 *
 * <p>Stop words are <b>kept in the index</b>. They are only dropped from a query when the
 * query has at least one other word (see {@link Tokenizer#queryTerms(String)}).
 */
public final class StopWords {

    public static final Set<String> ENGLISH = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "if", "in", "into",
            "is", "it", "no", "not", "of", "on", "or", "such", "that", "the", "their", "then",
            "there", "these", "they", "this", "to", "was", "will", "with");

    private StopWords() {
    }

    /** True if the (lowercase, unstemmed) word is a stop word. */
    public static boolean isStopWord(String word) {
        return ENGLISH.contains(word);
    }
}
