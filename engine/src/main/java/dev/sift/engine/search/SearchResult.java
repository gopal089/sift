package dev.sift.engine.search;

import java.util.List;

/**
 * The answer to one query.
 *
 * @param terms        the query terms actually searched (after the tokenizer and stop-word rule)
 * @param mode         AND if every term had to match, OR if we fell back to "any term", SOFT_AND if documents
 *                     with more matched terms rank first, NONE if no terms; SEMANTIC / HYBRID for Smart search
 * @param totalMatches how many documents matched (before keeping only the top K)
 * @param hits         the top K documents, best first
 */
public record SearchResult(List<String> terms, MatchMode mode, int totalMatches, List<Hit> hits) {

    public enum MatchMode { NONE, AND, OR, SOFT_AND, SEMANTIC, HYBRID }

    public record Hit(int docId, int wikiId, String title, double score) {
    }
}
