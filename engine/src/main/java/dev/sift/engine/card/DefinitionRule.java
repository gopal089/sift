package dev.sift.engine.card;

import dev.sift.engine.text.Tokenizer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Decides whether the top result is a confident enough match to show the definition card.
 *
 * <p>The rule: the query's terms, as a set, are <b>exactly</b> the terms of the top result's title, or of a
 * redirect title that leads to it. Terms come from {@link Tokenizer#queryTerms(String, boolean)}, the same
 * function that makes the query's terms, so both sides are lowercased, stop-worded and stemmed the same way.
 * <ul>
 *   <li>"french revolution" → [french, revolut]; title "French Revolution" → [french, revolut]: card.</li>
 *   <li>"einstein" → [einstein]; title "Albert Einstein" → [albert, einstein]: not equal, but the redirect
 *       "Einstein" leads to Albert Einstein and gives [einstein]: card.</li>
 *   <li>"what is the capital of australia" → [capit, australia]; title "Australia" → [australia]: no card.</li>
 * </ul>
 * Chosen on the main 50 queries (eval/results/definition-card-*.json): 40 cards in Smart and 39 in Keyword
 * mode, none of them for a wrong article. Ranking is never changed by this class; it only reads the top hit.
 */
public final class DefinitionRule {

    /** Why the card is shown. */
    public enum Match { NONE, TITLE, REDIRECT }

    private final Function<String, List<String>> redirectsTo;
    private final boolean dropQuestionWords;

    /**
     * @param redirectsTo       article title → the redirect titles that lead to it (empty list if none)
     * @param dropQuestionWords must be the same setting the query's terms were made with
     */
    public DefinitionRule(Function<String, List<String>> redirectsTo, boolean dropQuestionWords) {
        this.redirectsTo = redirectsTo;
        this.dropQuestionWords = dropQuestionWords;
    }

    public Match match(List<String> queryTerms, String topTitle) {
        if (queryTerms.isEmpty()) {
            return Match.NONE;
        }
        Set<String> query = new HashSet<>(queryTerms);
        if (query.equals(terms(topTitle))) {
            return Match.TITLE;
        }
        for (String redirect : redirectsTo.apply(topTitle)) {
            if (query.equals(terms(redirect))) {
                return Match.REDIRECT;
            }
        }
        return Match.NONE;
    }

    private Set<String> terms(String title) {
        return new HashSet<>(Tokenizer.queryTerms(title, dropQuestionWords));
    }
}
