package dev.sift.engine.card;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sift.engine.card.DefinitionRule.Match;
import dev.sift.engine.text.Tokenizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefinitionRuleTest {

    private static final Map<String, List<String>> REDIRECTS = Map.of(
            "Albert Einstein", List.of("Einstein", "A. Einstein"),
            "World War II", List.of("World War 2", "WWII"));

    private final DefinitionRule rule = new DefinitionRule(t -> REDIRECTS.getOrDefault(t, List.of()), true);

    private Match match(String query, String topTitle) {
        return rule.match(Tokenizer.queryTerms(query, true), topTitle);
    }

    @Test
    void sameWordsAsTheTitleShowTheCard() {
        assertEquals(Match.TITLE, match("french revolution", "French Revolution"));
        assertEquals(Match.TITLE, match("the history of india", "History of India"));   // stop words dropped
        assertEquals(Match.TITLE, match("fibonacci numbers", "Fibonacci number"));      // stemmed
        assertEquals(Match.TITLE, match("Revolution FRENCH", "French Revolution"));     // order and case
        assertEquals(Match.TITLE, match("the who", "The Who"));                          // all stop words: kept
    }

    @Test
    void aRedirectTitleThatLeadsToTheTopResultAlsoShowsIt() {
        assertEquals(Match.REDIRECT, match("einstein", "Albert Einstein"));
        assertEquals(Match.REDIRECT, match("world war 2", "World War II"));
    }

    @Test
    void moreOrFewerWordsThanTheTitleShowNoCard() {
        assertEquals(Match.NONE, match("what is the capital of australia", "Australia"));   // query has more
        assertEquals(Match.NONE, match("python programming", "Python (programming language)"));   // title has more
        assertEquals(Match.NONE, match("albert", "Albert Einstein"));
    }

    @Test
    void aRedirectToAnotherArticleDoesNotCount() {
        assertEquals(Match.NONE, match("einstein", "Theory of relativity"));
    }

    @Test
    void noTermsMeansNoCard() {
        assertEquals(Match.NONE, rule.match(List.of(), "Anything"));
    }
}
