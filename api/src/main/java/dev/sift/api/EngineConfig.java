package dev.sift.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.sift.engine.card.DefinitionRule;
import dev.sift.engine.card.RelatedArticles;
import dev.sift.engine.data.RedirectAliases;
import dev.sift.engine.embed.Embedder;
import dev.sift.engine.embed.QueryEmbedder;
import dev.sift.engine.search.Ranking;
import dev.sift.engine.search.Searcher;
import dev.sift.engine.search.SmartSearcher;
import dev.sift.engine.store.DiskIndex;
import dev.sift.engine.spell.SpellChecker;
import dev.sift.engine.suggest.TitleTrie;
import dev.sift.engine.vector.Hnsw;
import dev.sift.engine.vector.HnswIndex;
import dev.sift.engine.vector.SemanticIndex;
import dev.sift.engine.vector.StackedVectors;
import dev.sift.engine.vector.VectorFiles;
import dev.sift.engine.vector.VectorStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the engine objects once at startup. Spring calls each {@code @Bean} method one time and hands
 * the result to every class that asks for it (here: the controller).
 */
@Configuration
public class EngineConfig {

    private static final Logger LOG = LoggerFactory.getLogger(EngineConfig.class);

    /** Opens the on-disk index. If the folder is missing the server refuses to start, with a clear message. */
    /**
     * Finished search pages for repeated queries. At most {@code sift.cache-size} entries (0 = off); when it is
     * full, Caffeine drops the entries least likely to be used again. No expiry: the index never changes while
     * the server runs, so a cached page never goes stale.
     */
    @Bean
    public Cache<String, ApiModels.SearchResponse> searchCache(@Value("${sift.cache-size}") long size) {
        return Caffeine.newBuilder().maximumSize(size).recordStats().build();
    }

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

    /** The embedding model (ONNX) with our WordPiece tokenizer. Missing model files: the server refuses to start. */
    @Bean(destroyMethod = "close")
    public QueryEmbedder queryEmbedder(@Value("${sift.model-dir}") String modelDir) throws IOException {
        return QueryEmbedder.open(Path.of(modelDir));
    }

    /**
     * The meaning index: one or two sets of article vectors (float32 or int8, each checked against the
     * index's titles) and the HNSW graph built over them.
     */
    @Bean
    public VectorStore articleVectors(DiskIndex index, @Value("${sift.vectors-dirs}") String vectorsDirs,
                                      @Value("${sift.vector-kind}") String kind) throws IOException {
        List<VectorStore> stores = new ArrayList<>();
        for (String dir : vectorsDirs.split(",")) {
            stores.add(VectorFiles.open(Path.of(dir.strip()), index.docs(), kind));
        }
        return stores.size() == 1 ? stores.get(0) : new StackedVectors(stores.toArray(new VectorStore[0]));
    }

    @Bean
    public SemanticIndex semanticIndex(DiskIndex index, VectorStore articleVectors,
                                       @Value("${sift.vectors-dirs}") String vectorsDirs,
                                       @Value("${sift.graph-file}") String graphFile,
                                       @Value("${sift.ef-search}") int efSearch) throws IOException {
        Hnsw graph = Hnsw.load(Path.of(graphFile), articleVectors);
        return new HnswIndex(vectorsDirs, graph, efSearch, index.docs().size());
    }

    /**
     * Redirect titles per article ("Einstein" → Albert Einstein), used only by the definition card's rule.
     * If the file is missing the card still works, with exact title matches only.
     */
    @Bean
    public RedirectAliases redirectAliases(@Value("${sift.redirects-file}") String file) throws IOException {
        Path path = Path.of(file);
        if (!Files.exists(path)) {
            LOG.warn("No redirects file at {}: the definition card will use exact title matches only", path);
            return RedirectAliases.of(List.of());
        }
        return RedirectAliases.load(path);
    }

    @Bean
    public DefinitionRule definitionRule(RedirectAliases redirects, Searcher searcher) {
        return new DefinitionRule(redirects::aliasesOf, searcher.ranking().dropQuestionWords());
    }

    @Bean
    public RelatedArticles relatedArticles(VectorStore articleVectors, SemanticIndex semanticIndex) {
        return new RelatedArticles(articleVectors, semanticIndex);
    }

    /** Smart mode: semantic-only or hybrid (RRF), as chosen in Phase 5 (see PROGRESS.md). */
    @Bean
    public SmartSearcher smartSearcher(Searcher keyword, Embedder embedder, SemanticIndex semantic, DiskIndex index,
                                       @Value("${sift.smart-mode}") String mode,
                                       @Value("${sift.keyword-weight}") double keywordWeight) {
        return new SmartSearcher(keyword, embedder, semantic, index.docs(),
                SmartSearcher.Mode.valueOf(mode.toUpperCase(Locale.ROOT)), keywordWeight);
    }
}
