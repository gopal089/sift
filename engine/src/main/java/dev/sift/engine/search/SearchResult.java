package dev.sift.engine.search;

import java.util.List;

/**
 * The answer to one query.
 *
 * @param terms        the query terms actually searched (after the tokenizer and stop-word rule)
 * @param mode         AND if every term had to match, OR if we fell back to "any term", NONE if no terms
 * @param totalMatches how many documents matched (before keeping only the top K)
 * @param hits         the top K documents, best first
 */
public record SearchResult(List<String> terms, MatchMode mode, int totalMatches, List<Hit> hits) {

    public enum MatchMode { NONE, AND, OR }

    public record Hit(int docId, int wikiId, String title, double score) {
    }
}
