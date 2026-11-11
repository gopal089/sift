package dev.sift.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.sift.engine.index.IndexBuilder;
import dev.sift.engine.store.IndexFileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Starts the real application against a tiny index in a temp folder and calls the endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
class SearchApiTest {

    private static final Path INDEX_DIR = writeTinyIndex();

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void indexDir(DynamicPropertyRegistry registry) {
        registry.add("sift.index-dir", INDEX_DIR::toString);
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
                writer.write(builder.build(), false);
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        // "sunlight" is word 8 of a long article: the window starts 5 words before it and is 30 words long
        mvc.perform(get("/search").param("q", "sunlight leaves"))
                .andExpect(jsonPath("$.results[0].title").value("Photosynthesis"))
                .andExpect(jsonPath("$.results[0].snippet", org.hamcrest.Matchers.startsWith("… green plants make")))
                .andExpect(jsonPath("$.results[0].snippet", org.hamcrest.Matchers.endsWith(" …")))
                .andExpect(jsonPath("$.results[0].highlights[0].start").value(30))   // "sunlight"
                .andExpect(jsonPath("$.results[0].highlights[0].end").value(38))
                .andExpect(jsonPath("$.results[0].highlights.length()", greaterThan(1)));
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
    void smartModeFallsBackToKeywordAndSaysSo() throws Exception {
        mvc.perform(get("/search").param("q", "plants").param("mode", "smart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("smart"))
                .andExpect(jsonPath("$.modeUsed").value("keyword"))
                .andExpect(jsonPath("$.notice", containsString("Phase 5")));
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
                .andExpect(jsonPath("$.cache.enabled").value(false));
    }

    @Test
    void healthIsOk() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
