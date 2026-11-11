package dev.sift.engine.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sift.engine.index.FieldPostingList;
import dev.sift.engine.index.IndexBuilder;
import dev.sift.engine.index.InvertedIndex;
import dev.sift.engine.index.PostingList;
import dev.sift.engine.search.Ranking;
import dev.sift.engine.search.Searcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Write a small index to disk, read it back, and check that nothing changed. */
class DiskIndexTest {

    private static final String[][] DOCS = {
            {"Photosynthesis", "Photosynthesis is how green plants make food from sunlight. Plants need sunlight."},
            {"Plant", "A plant is a living thing. Plants grow in soil and need sunlight."},
            {"Café Ångström", "A café named after Ångström. 東京 and naïve are not ASCII; 🙂 is an emoji."},
            {"United States", "A country in North America."},
            {"Empty", ""},
            {"The Who", "The Who are an English rock band."},
    };
    private static final List<List<String>> ALIASES = List.of(
            List.of(), List.of("Plants", "Flora"), List.of(), List.of("USA", "U.S.A.", "United States of America"),
            List.of(), List.of());

    @TempDir
    static Path dir;
    private static InvertedIndex memory;
    private static DiskIndex disk;
    private static IndexMeta meta;

    @BeforeAll
    static void writeAndOpen() throws IOException {
        IndexBuilder builder = new IndexBuilder();
        try (IndexFileWriter writer = new IndexFileWriter(dir)) {
            for (int d = 0; d < DOCS.length; d++) {
                builder.addDocument(100 + d, DOCS[d][0], DOCS[d][1], ALIASES.get(d));
                writer.appendText(DOCS[d][1]);
            }
            memory = builder.build();
            meta = writer.write(memory, true);
        }
        disk = DiskIndex.open(dir);
    }

    @Test
    void everyTermHasIdenticalPostingsPositionsTitleAndAliasLists() {
        assertEquals(memory.termCount(), disk.dictionary().size());
        for (String term : memory.terms()) {
            PostingList m = memory.postings(term);
            PostingList d = disk.postings(term);
            assertNotNull(d, term);
            assertArrayEquals(m.docIds(), d.docIds(), term);
            for (int i = 0; i < m.size(); i++) {
                assertEquals(m.freq(i), d.freq(i), term);
                assertArrayEquals(m.positions(i), d.positions(i), term);
            }
            assertSameField(memory.titlePostings(term), disk.titlePostings(term), term);
            assertSameField(memory.aliasPostings(term), disk.aliasPostings(term), term);
        }
    }

    private static void assertSameField(FieldPostingList m, FieldPostingList d, String term) {
        if (m == null) {
            assertNull(d, term);
            return;
        }
        assertNotNull(d, term);
        assertEquals(m.size(), d.size(), term);
        for (int i = 0; i < m.size(); i++) {
            assertEquals(m.docId(i), d.docId(i), term);
            assertEquals(m.freq(i), d.freq(i), term);
        }
    }

    @Test
    void dictionaryIsSortedAndFindsEveryTermIncludingNonAscii() {
        TermDictionary dict = disk.dictionary();
        for (int i = 0; i < dict.size(); i++) {
            assertEquals(i, dict.find(dict.term(i)));
        }
        assertTrue(dict.find("東京") >= 0);
        assertTrue(dict.find("cafe") >= 0);       // accents are folded by the tokenizer
        assertEquals(-1, dict.find("zzzz"));
        assertEquals(-1, dict.find(""));
        assertEquals(-1, dict.find("a0"));        // between existing terms
        assertNull(disk.postings("zzzz"));
        assertNull(disk.titlePostings("zzzz"));
        assertNull(disk.aliasPostings("sunlight"));   // a body-only word has no alias block
    }

    @Test
    void docTableTitlesAndTextComeBackUnchanged() {
        assertEquals(memory.docs().size(), disk.docs().size());
        for (int d = 0; d < DOCS.length; d++) {
            assertEquals(100 + d, disk.docs().wikiId(d));
            assertEquals(DOCS[d][0], disk.docs().title(d));
            assertEquals(memory.docs().titleLength(d), disk.docs().titleLength(d));
            assertEquals(memory.docs().bodyLength(d), disk.docs().bodyLength(d));
            assertEquals(memory.docs().aliasLength(d), disk.docs().aliasLength(d));
            assertEquals(DOCS[d][1], disk.text(d));
        }
        assertEquals(memory.docs().averageBodyLength(), disk.docs().averageBodyLength(), 0.0);
        assertEquals("A plant", disk.text(1, 7));   // only the first bytes
        assertEquals("", disk.text(4));             // the empty article
    }

    @Test
    void searchGivesIdenticalHitsFromDiskAndMemory() {
        for (String spec : List.of("bm25", "bm25f:w=3", "bm25sum:w=2,bb=0.3,qstop=1", "bm25sum:w=2,bb=0.3,wa=1,soft=1")) {
            Ranking ranking = Ranking.parse(spec);
            for (String query : List.of("sunlight", "plants need sunlight", "usa", "the who", "café", "東京",
                    "what is a plant", "nothing matches zzzz", "")) {
                assertEquals(new Searcher(memory, ranking).search(query, 10),
                        new Searcher(disk, ranking).search(query, 10), spec + " / " + query);
            }
        }
    }

    @Test
    void metaRecordsCountsSizesAndCompression() throws IOException {
        assertEquals(DOCS.length, meta.documents());
        assertEquals(memory.termCount(), meta.terms());
        assertTrue(meta.withAliases());
        assertEquals(Files.size(dir.resolve("postings.bin")), meta.fileSizes().get("postings.bin"));
        // raw = 4 bytes per int; every number here is small, so each takes 1 byte when compressed
        assertEquals(4 * memory.postingCount(), meta.rawBytes().get("docIds"));
        assertEquals(memory.positionCount(), meta.compressedBytes().get("positions"));
        assertEquals(4 * memory.positionCount(), meta.rawBytes().get("positions"));
    }

    @Test
    void folderWithoutMetaIsNotAnIndex(@TempDir Path empty) {
        IOException e = assertThrows(IOException.class, () -> DiskIndex.open(empty));
        assertTrue(e.getMessage().contains("meta.json"), e.getMessage());
    }

    @Test
    void truncatedFileIsDetected(@TempDir Path copy) throws IOException {
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                Files.copy(f, copy.resolve(f.getFileName()));
            }
        }
        Path postings = copy.resolve("postings.bin");
        byte[] bytes = Files.readAllBytes(postings);
        Files.write(postings, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        IOException e = assertThrows(IOException.class, () -> DiskIndex.open(copy));
        assertTrue(e.getMessage().contains("postings.bin"), e.getMessage());
    }
}
