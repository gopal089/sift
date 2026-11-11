package dev.sift.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.sift.engine.embed.Embedder;
import dev.sift.engine.index.IndexBuilder;
import dev.sift.engine.index.InvertedIndex;
import dev.sift.engine.store.IndexFileWriter;
import dev.sift.engine.vector.Float32Vectors;
import dev.sift.engine.vector.Hnsw;
import dev.sift.engine.vector.VectorFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Starts the real application against a tiny index (+ tiny 4-number vectors and HNSW graph) in a temp folder
 * and calls the endpoints. The embedding model is replaced by a fake that always points at "Photosynthesis",
 * so the tests do not need the 90 MB model file.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SearchApiTest {

    private static final Path INDEX_DIR = writeTinyIndex();
    private static final Path VECTORS_DIR = INDEX_DIR.resolve("vectors");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private Embedder embedder;

    @DynamicPropertySource
    static void indexDir(DynamicPropertyRegistry registry) {
        registry.add("sift.index-dir", INDEX_DIR::toString);
        registry.add("sift.vectors-dir", VECTORS_DIR::toString);
        registry.add("sift.vector-kind", () -> "float32");
        registry.add("sift.smart-mode", () -> "hybrid");
        registry.add("sift.ef-search", () -> "50");
    }

    @BeforeEach
    void fakeModel() {
        when(embedder.embed(anyString())).thenReturn(new float[] {1, 0, 0, 0});
    }

    private static Path writeTinyIndex() {
        try {
            Path dir = Files.createTempDirectory("sift-api-test-index");
            IndexBuilder builder = new IndexBuilder();
            try (IndexFileWriter writer = new IndexFileWriter(dir)) {
                String[][] docs = new String[15][];
                docs[0] = new String[] {"Photosynthesis", "Photosynthesis is how green plants make food from sunlight. "
                        + "It happens in the leaves. ".repeat(20)};
                docs[1] = new String[] {"Plant", "A plant is a living thing. Plants grow in soil and need sunlight."};
                docs[2] = new String[] {"C++ (programming language)", "C++ is a programming language."};
                for (int i = 3; i < 15; i++) {   // 12 pages about rivers, to test pagination
                    docs[i] = new String[] {"River " + i, "River number " + i + " is a long river with water."};
                }
                for (int d = 0; d < docs.length; d++) {
                    builder.addDocument(1000 + d, docs[d][0], docs[d][1]);
                    writer.appendText(docs[d][1]);
                }
                InvertedIndex index = builder.build();
                writer.write(index, false);
                writeTinyVectors(dir.resolve("vectors"), index);
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Photosynthesis → (1,0,0,0), Plant → (0.8,0.6,0,0), C++ → (0,0,1,0), rivers → near (0,0,0,1). */
    private static void writeTinyVectors(Path dir, InvertedIndex index) throws IOException {
        Files.createDirectories(dir);
        int n = index.docs().size();
        float[] rows = new float[n * 4];
        float[][] first = {{1, 0, 0, 0}, {0.8f, 0.6f, 0, 0}, {0, 0, 1, 0}};
        for (int d = 0; d < n; d++) {
            float[] v = d < 3 ? first[d] : new float[] {0, 0.01f * d, 0, 1};
            double norm = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2] + v[3] * v[3]);
            for (int i = 0; i < 4; i++) {
                rows[d * 4 + i] = (float) (v[i] / norm);
            }
        }
        ByteBuffer bytes = ByteBuffer.allocate(rows.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asFloatBuffer().put(rows);
        Files.write(dir.resolve(VectorFiles.FLOAT32), bytes.array());
        Files.writeString(dir.resolve(VectorFiles.META), String.format(
                "{\"count\": %d, \"dimensions\": 4, \"model\": \"test\", \"titles_sha256\": \"%s\"}",
                n, VectorFiles.titlesSha256(index.docs())));
        Hnsw.build(Float32Vectors.of(rows, 4), 4, 16, 1, null).save(dir.resolve(VectorFiles.GRAPH));
    }

    // ------------------------------------------------------------------ /search

    @Test
    void searchReturnsResultsWithAllFields() throws Exception {
        mvc.perform(get("/search").param("q", "photosynthesis"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.query").value("photosynthesis"))
                .andExpect(jsonPath("$.mode").value("keyword"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalMatches").value(1))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].id").value(1000))
                .andExpect(jsonPath("$.results[0].title").value("Photosynthesis"))
                .andExpect(jsonPath("$.results[0].url").value("https://simple.wikipedia.org/wiki/Photosynthesis"))
                .andExpect(jsonPath("$.results[0].snippet", containsString("green plants make food")))
                .andExpect(jsonPath("$.results[0].score", greaterThan(0.0)))
                .andExpect(jsonPath("$.stats.documentsSearched").value(15))
                .andExpect(jsonPath("$.stats.terms", contains("photosynthesi")))
                .andExpect(jsonPath("$.stats.matchMode").value("AND"))
                .andExpect(jsonPath("$.stats.cache").value("off"))
                .andExpect(jsonPath("$.stats.tookMs", greaterThan(0.0)));
    }

    @Test
    void snippetIsAWindowOfTheTextWithHighlightRanges() throws Exception {
        // the article is long: the snippet is a 30-word window around "sunlight" and "leaves", cut on both sides
        mvc.perform(get("/search").param("q", "sunlight leaves"))
                .andExpect(jsonPath("$.results[0].title").value("Photosynthesis"))
                .andExpect(jsonPath("$.results[0].snippet", org.hamcrest.Matchers.startsWith("… ")))
                .andExpect(jsonPath("$.results[0].snippet", containsString("sunlight. It happens in the leaves")))
                .andExpect(jsonPath("$.results[0].snippet", org.hamcrest.Matchers.endsWith(" …")))
                .andExpect(jsonPath("$.results[0].highlights.length()", greaterThan(1)))
                .andExpect(jsonPath("$.results[0].highlights[0].start", greaterThan(1)));
    }

    @Test
    void urlEncodesSpecialCharactersInTitles() throws Exception {
        mvc.perform(get("/search").param("q", "programming language"))
                .andExpect(jsonPath("$.results[0].url")
                        .value("https://simple.wikipedia.org/wiki/C++_(programming_language)"));
    }

    @Test
    void paginationGivesTenThenTheRest() throws Exception {
        mvc.perform(get("/search").param("q", "river"))
                .andExpect(jsonPath("$.totalMatches").value(12))
                .andExpect(jsonPath("$.results", hasSize(10)))
                .andExpect(jsonPath("$.hasMore").value(true));
        mvc.perform(get("/search").param("q", "river").param("page", "2"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.results", hasSize(2)))
                .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/search").param("q", "river").param("page", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(0)));
    }

    @Test
    void smartModeFindsByMeaningWhenNoWordMatches() throws Exception {
        // no article contains "chlorophyll": keyword mode finds nothing ...
        mvc.perform(get("/search").param("q", "chlorophyll"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMatches").value(0))
                .andExpect(jsonPath("$.stats.embedMs").doesNotExist());
        // ... Smart mode ranks by meaning (the fake model points at Photosynthesis)
        mvc.perform(get("/search").param("q", "chlorophyll").param("mode", "smart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("smart"))
                .andExpect(jsonPath("$.modeUsed").value("smart"))
                .andExpect(jsonPath("$.notice").doesNotExist())
                .andExpect(jsonPath("$.results[0].title").value("Photosynthesis"))
                .andExpect(jsonPath("$.results[1].title").value("Plant"))
                .andExpect(jsonPath("$.results", hasSize(10)))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.results[0].highlights", hasSize(0)))     // no query word in the text
                .andExpect(jsonPath("$.results[0].snippet", containsString("Photosynthesis is how")))
                .andExpect(jsonPath("$.stats.matchMode").value("HYBRID"))
                .andExpect(jsonPath("$.stats.embedMs").exists())
                .andExpect(jsonPath("$.stats.vectorMs").exists());
    }

    @Test
    void smartModeStillBoldsQueryWordsThatAreInTheText() throws Exception {
        mvc.perform(get("/search").param("q", "photosynthesis").param("mode", "smart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].title").value("Photosynthesis"))
                .andExpect(jsonPath("$.results[0].highlights", hasSize(greaterThan(0))));
    }

    @Test
    void smartModeWithoutLettersGivesTheSameFriendlyNotice() throws Exception {
        mvc.perform(get("/search").param("q", "?!?").param("mode", "smart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(0)))
                .andExpect(jsonPath("$.notice", containsString("no letters or numbers")));
    }

    @Test
    void misspelledWordGetsADidYouMean() throws Exception {
        // "river" is in 12 documents, so it is in the spelling vocabulary; "rivr" is not a word
        mvc.perform(get("/search").param("q", "Long rivr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.didYouMean").value("long river"));
        mvc.perform(get("/search").param("q", "long river"))
                .andExpect(jsonPath("$.didYouMean").doesNotExist());
        mvc.perform(get("/search").param("q", "zzzzzzzzzz"))
                .andExpect(jsonPath("$.didYouMean").doesNotExist());
    }

    // ------------------------------------------------------------------ bad or strange input: never a 500

    @Test
    void missingQueryIs400() throws Exception {
        mvc.perform(get("/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_PARAMETER"));
    }

    @Test
    void emptyOrBlankQueryIs400() throws Exception {
        for (String q : new String[] {"", "   ", "\t\n"}) {
            mvc.perform(get("/search").param("q", q))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("EMPTY_QUERY"))
                    .andExpect(jsonPath("$.error.message").isNotEmpty());
        }
    }

    @Test
    void queryOf200CharactersIsFineBut201IsTooLong() throws Exception {
        mvc.perform(get("/search").param("q", "a".repeat(200))).andExpect(status().isOk());
        mvc.perform(get("/search").param("q", "a".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("QUERY_TOO_LONG"));
        mvc.perform(get("/search").param("q", "plant ".repeat(2000)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emojiAndSymbolsGiveZeroResultsAndAHint() throws Exception {
        for (String q : new String[] {"🙂🙂🙂", "!!! ???", "🙂 + 🌋"}) {
            mvc.perform(get("/search").param("q", q))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalMatches").value(0))
                    .andExpect(jsonPath("$.results", hasSize(0)))
                    .andExpect(jsonPath("$.notice", containsString("no letters or numbers")));
        }
    }

    @Test
    void emojiMixedWithWordsStillSearchesTheWords() throws Exception {
        mvc.perform(get("/search").param("q", "🌱 plants 🙂"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].title").exists());
    }

    @Test
    void nonsenseAndOddInputGiveZeroResultsNotAnError() throws Exception {
        for (String q : new String[] {"qwertyuiopasdfgh", "'; DROP TABLE articles;--", "<script>alert(1)</script>zzqq",
                "%00%ff", "東京タワー", "a\u0000b"}) {
            mvc.perform(get("/search").param("q", q))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.results").isArray());
        }
    }

    @Test
    void badModeAndBadPageAre400() throws Exception {
        mvc.perform(get("/search").param("q", "plants").param("mode", "magic"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_MODE"));
        for (String page : new String[] {"0", "-1", "101", "99999999"}) {
            mvc.perform(get("/search").param("q", "plants").param("page", page))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("BAD_PAGE"));
        }
        for (String page : new String[] {"abc", "1.5", "99999999999999999999"}) {
            mvc.perform(get("/search").param("q", "plants").param("page", page))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("BAD_PARAMETER"));
        }
        // an empty "page=" counts as "not given": Spring uses the default, page 1
        mvc.perform(get("/search").param("q", "plants").param("page", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    void unknownPathIs404JsonAndPostIs405Json() throws Exception {
        mvc.perform(get("/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(post("/search").param("q", "plants"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    // ------------------------------------------------------------------ /suggest, /stats, /health

    @Test
    void suggestReturnsUpToFiveTitles() throws Exception {
        mvc.perform(get("/suggest").param("prefix", "p"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions", contains("Photosynthesis", "Plant")));
        mvc.perform(get("/suggest").param("prefix", "river"))
                .andExpect(jsonPath("$.suggestions", hasSize(5)));
    }

    @Test
    void suggestWithEmptyMissingOrOddPrefixIsAnEmptyList() throws Exception {
        mvc.perform(get("/suggest")).andExpect(status().isOk()).andExpect(jsonPath("$.suggestions", hasSize(0)));
        mvc.perform(get("/suggest").param("prefix", "")).andExpect(jsonPath("$.suggestions", hasSize(0)));
        mvc.perform(get("/suggest").param("prefix", "🙂")).andExpect(jsonPath("$.suggestions", hasSize(0)));
        mvc.perform(get("/suggest").param("prefix", "x".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PREFIX_TOO_LONG"));
    }

    @Test
    void statsReportsIndexNumbers() throws Exception {
        mvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(15))
                .andExpect(jsonPath("$.terms", greaterThan(10)))
                .andExpect(jsonPath("$.indexBytes", greaterThan(0)))
                .andExpect(jsonPath("$.indexFiles['postings.bin']", greaterThan(0)))
                .andExpect(jsonPath("$.postingBytesBeforeCompression", greaterThan(0)))
                .andExpect(jsonPath("$.ranking", containsString("BM25")))
                .andExpect(jsonPath("$.smartRanking", containsString("hybrid RRF k=60")))
                .andExpect(jsonPath("$.vectorBytes").value(15 * 4 * 4))
                .andExpect(jsonPath("$.cache.enabled").value(false));
    }

    @Test
    void healthIsOk() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
