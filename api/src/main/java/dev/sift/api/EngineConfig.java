package dev.sift.api;

import dev.sift.engine.embed.Embedder;
import dev.sift.engine.embed.QueryEmbedder;
import dev.sift.engine.search.Ranking;
import dev.sift.engine.search.Searcher;
import dev.sift.engine.search.SmartSearcher;
import dev.sift.engine.store.DiskIndex;
import dev.sift.engine.spell.SpellChecker;
import dev.sift.engine.suggest.TitleTrie;
import dev.sift.engine.vector.Hnsw;
import dev.sift.engine.vector.VectorFiles;
import dev.sift.engine.vector.VectorStore;
import java.io.IOException;
import java.util.Locale;
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

    /** The embedding model (ONNX) with our WordPiece tokenizer. Missing model files: the server refuses to start. */
    @Bean(destroyMethod = "close")
    public QueryEmbedder queryEmbedder(@Value("${sift.model-dir}") String modelDir) throws IOException {
        return QueryEmbedder.open(Path.of(modelDir));
    }

    /** The article vectors (float32 or int8), checked against the index's titles, plus the HNSW graph. */
    @Bean
    public Hnsw hnsw(DiskIndex index, @Value("${sift.vectors-dir}") String vectorsDir,
                     @Value("${sift.vector-kind}") String kind) throws IOException {
        VectorStore vectors = VectorFiles.open(Path.of(vectorsDir), index.docs(), kind);
        return VectorFiles.openGraph(Path.of(vectorsDir), vectors);
    }

    /** Smart mode: semantic-only or hybrid (RRF), as chosen in Phase 5 (see eval/results/smart-main.txt). */
    @Bean
    public SmartSearcher smartSearcher(Searcher keyword, Embedder embedder, Hnsw graph, DiskIndex index,
                                       @Value("${sift.smart-mode}") String mode,
                                       @Value("${sift.ef-search}") int efSearch,
                                       @Value("${sift.vector-kind}") String kind) {
        return new SmartSearcher(keyword, embedder, graph, index.docs(),
                SmartSearcher.Mode.valueOf(mode.toUpperCase(Locale.ROOT)), efSearch, kind);
    }
}
