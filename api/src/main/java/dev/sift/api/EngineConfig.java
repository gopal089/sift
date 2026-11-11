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
import dev.sift.engine.vector.HnswIndex;
import dev.sift.engine.vector.SemanticIndex;
import dev.sift.engine.vector.StackedVectors;
import dev.sift.engine.vector.VectorFiles;
import dev.sift.engine.vector.VectorStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * The meaning index: one or two sets of article vectors (float32 or int8, each checked against the
     * index's titles) and the HNSW graph built over them.
     */
    @Bean
    public SemanticIndex semanticIndex(DiskIndex index, @Value("${sift.vectors-dirs}") String vectorsDirs,
                                       @Value("${sift.graph-file}") String graphFile,
                                       @Value("${sift.vector-kind}") String kind,
                                       @Value("${sift.ef-search}") int efSearch) throws IOException {
        List<VectorStore> stores = new ArrayList<>();
        for (String dir : vectorsDirs.split(",")) {
            stores.add(VectorFiles.open(Path.of(dir.strip()), index.docs(), kind));
        }
        VectorStore all = stores.size() == 1 ? stores.get(0) : new StackedVectors(stores.toArray(new VectorStore[0]));
        Hnsw graph = Hnsw.load(Path.of(graphFile), all);
        return new HnswIndex(vectorsDirs, graph, efSearch, index.docs().size());
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
