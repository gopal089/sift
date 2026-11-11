package dev.sift.api;

import dev.sift.engine.search.Ranking;
import dev.sift.engine.search.Searcher;
import dev.sift.engine.store.DiskIndex;
import dev.sift.engine.spell.SpellChecker;
import dev.sift.engine.suggest.TitleTrie;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the engine objects once at startup. Spring calls each {@code @Bean} method one time and hands
 * the result to every class that asks for it (here: the controller).
 */
@Configuration
public class EngineConfig {

    /** Opens the on-disk index. If the folder is missing the server refuses to start, with a clear message. */
    @Bean
    public DiskIndex diskIndex(@Value("${sift.index-dir}") String indexDir) throws IOException {
        return DiskIndex.open(Path.of(indexDir));
    }

    @Bean
    public Searcher searcher(DiskIndex index) {
        return new Searcher(index, Ranking.DEFAULT);
    }

    /** Autocomplete: a trie of all titles, each node knowing its 5 most important completions. */
    @Bean
    public TitleTrie titleTrie(DiskIndex index) {
        return new TitleTrie(index.docs());
    }

    /** "Did you mean": a BK-tree over the real words of the collection. */
    @Bean
    public SpellChecker spellChecker(DiskIndex index) {
        return new SpellChecker(index.vocabulary());
    }
}
