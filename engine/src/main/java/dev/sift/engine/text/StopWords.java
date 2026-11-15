package dev.sift.engine.text;

import java.util.HashSet;
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

    /**
     * Words that turn a query into a question ("who invented the telephone"). The answer article rarely
     * contains them, so requiring them (AND) would hide it. Dropped at query time only, like the list above.
     */
    public static final Set<String> QUESTION = Set.of(
            "who", "what", "when", "where", "why", "how", "did", "does", "is");

    /**
     * Words that are never worth <b>highlighting</b> in a snippet: the two lists above plus other helper words
     * (do, have, can, my, from...). Marking "do" in "how do plants make food" only adds noise.
     * Used for snippets only; it does not change which words are searched or how results are ranked.
     * "us" and "may" are left out on purpose: they are also "US" and the month.
     */
    public static final Set<String> NOT_HIGHLIGHTED = union(ENGLISH, QUESTION, Set.of(
            "which", "whom", "whose", "do", "doing", "done", "am", "were", "been", "being", "have", "has", "had",
            "having", "can", "could", "would", "should", "shall", "might", "must", "i", "me", "my", "you", "your",
            "he", "him", "his", "she", "her", "its", "we", "our", "them", "from", "about", "than", "so", "some",
            "any"));

    private StopWords() {
    }

    /** True if the (lowercase, unstemmed) word is in the basic 33-word list. */
    public static boolean isStopWord(String word) {
        return ENGLISH.contains(word);
    }

    /** True if the word is in the basic list or, when {@code withQuestionWords}, in the question list. */
    public static boolean isStopWord(String word, boolean withQuestionWords) {
        return ENGLISH.contains(word) || (withQuestionWords && QUESTION.contains(word));
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        HashSet<String> all = new HashSet<>();
        for (Set<String> set : sets) {
            all.addAll(set);
        }
        return Set.copyOf(all);
    }
}
