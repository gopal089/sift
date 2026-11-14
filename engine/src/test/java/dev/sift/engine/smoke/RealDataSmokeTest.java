package dev.sift.engine.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.sift.engine.embed.QueryEmbedder;
import dev.sift.engine.search.Ranking;
import dev.sift.engine.search.SearchResult;
import dev.sift.engine.search.Searcher;
import dev.sift.engine.search.SmartSearcher;
import dev.sift.engine.store.DiskIndex;
import dev.sift.engine.vector.HnswIndex;
import dev.sift.engine.vector.VectorFiles;
import dev.sift.engine.vector.VectorStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end check on the REAL data (the 280,748-article index, bge vectors, HNSW graph and ONNX model).
 * Those files are built locally and never in git, so this test is tagged {@code needs-data}: CI runs
 * {@code ./gradlew test -Pci}, which excludes the tag. Locally it also skips itself if the files are missing.
 */
@Tag("needs-data")
class RealDataSmokeTest {

    private static final Path DATA = Path.of(System.getProperty("sift.dataDir", "../data"));

    private static List<String> titles(SearchResult r, int n) {
        return r.hits().stream().limit(n).map(SearchResult.Hit::title).toList();
    }

    @Test
    void keywordAndSmartGiveTheKnownAnswersOnTheRealIndex() throws Exception {
        Path indexDir = DATA.resolve("index");
        Path vectorDir = DATA.resolve("vectors-bge60");
        Path modelDir = DATA.resolve("model-bge");
        assumeTrue(Files.isDirectory(indexDir) && Files.exists(vectorDir.resolve(VectorFiles.GRAPH))
                && Files.exists(modelDir.resolve("model.onnx")), "real data not built here");

        DiskIndex index = DiskIndex.open(indexDir);
        assertEquals(280_748, index.docs().size());
        Searcher keyword = new Searcher(index, Ranking.DEFAULT);
        assertEquals("Photosynthesis", titles(keyword.search("photosynthesis", 10), 1).get(0));
        assertEquals("World War II", titles(keyword.search("world war 2", 10), 1).get(0));

        VectorStore vectors = VectorFiles.open(vectorDir, index.docs(), "int8");
        HnswIndex semantic = new HnswIndex("bge60", VectorFiles.openGraph(vectorDir, vectors), 400, index.docs().size());
        try (QueryEmbedder embedder = QueryEmbedder.open(modelDir)) {
            SmartSearcher smart = new SmartSearcher(keyword, embedder, semantic, index.docs(),
                    SmartSearcher.Mode.HYBRID, 1);
            List<String> top3 = titles(smart.search("how do plants make food", 10), 3);
            assertTrue(top3.contains("Photosynthesis"), "Phase 5 'Done when': got " + top3);
        }
    }
}
