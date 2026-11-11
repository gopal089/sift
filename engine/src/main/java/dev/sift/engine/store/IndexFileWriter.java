package dev.sift.engine.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sift.engine.data.ArticleReader;
import dev.sift.engine.data.RedirectAliases;
import dev.sift.engine.index.DocTable;
import dev.sift.engine.index.FieldPostingList;
import dev.sift.engine.index.IndexBuilder;
import dev.sift.engine.index.InvertedIndex;
import dev.sift.engine.index.PostingList;
import dev.sift.engine.index.Vocabulary;
import dev.sift.engine.rank.LinkGraph;
import dev.sift.engine.rank.PageRank;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes an index folder (see {@code docs/learning/phase-2.md} for the layout).
 *
 * <p>Usage: call {@link #appendText} once per document, in doc id order, while indexing (so the 400 MB of
 * article text goes straight to disk and never sits in memory); then {@link #write} with the finished index.
 *
 * <p>Posting lists are compressed with <b>gap encoding + varints</b>:
 * <pre>
 *   doc ids    [3, 9, 12, 500]  → gaps [3, 6, 3, 488]     → bytes 03 06 03 E8 03   (5 bytes instead of 16)
 *   positions  per document, also as gaps: [0, 14, 15]    → [0, 14, 1]
 * </pre>
 */
public final class IndexFileWriter implements Closeable {

    static final String META = "meta.json";
    static final String DICTIONARY = "dictionary.bin";
    static final String POSTINGS = "postings.bin";
    static final String TITLE = "title.bin";
    static final String ALIAS = "alias.bin";
    static final String DOCS = "docs.bin";
    static final String TITLES = "titles.bin";
    static final String TEXT = "text.bin";
    static final String PAGERANK = "pagerank.bin";
    static final String WORDS = "words.bin";

    private final Path dir;
    private final CountingStream text;
    private final List<Long> textOffsets = new ArrayList<>();

    public IndexFileWriter(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        Files.deleteIfExists(dir.resolve(META));   // until meta.json is rewritten, this is not a valid index
        this.text = new CountingStream(dir.resolve(TEXT));
    }

    /** Stores the next document's text. Must be called once per document, in doc id order. */
    public void appendText(String articleText) throws IOException {
        textOffsets.add(text.count);
        text.write(articleText.getBytes(StandardCharsets.UTF_8));
    }

    /** Writes all other files and finally {@code meta.json}. */
    public IndexMeta write(InvertedIndex index, boolean withAliases) throws IOException {
        DocTable docs = index.docs();
        if (textOffsets.size() != docs.size()) {
            throw new IllegalStateException("appendText was called " + textOffsets.size() + " times for "
                    + docs.size() + " documents");
        }
        textOffsets.add(text.count);   // one extra entry: where the last text ends
        text.close();

        // Sort the terms by their UTF-8 bytes (unsigned), the order TermDictionary's binary search expects.
        List<byte[]> terms = new ArrayList<>(index.termCount());
        for (String term : index.terms()) {
            terms.add(term.getBytes(StandardCharsets.UTF_8));
        }
        terms.sort(Arrays::compareUnsigned);

        Map<String, Long> raw = new LinkedHashMap<>();
        Map<String, Long> compressed = new LinkedHashMap<>();
        long[] counts = new long[3];       // ints written: doc ids, freqs, positions
        long[] bytes = new long[3];        // bytes used:   doc ids, freqs, positions
        long titleInts = 0;
        long aliasInts = 0;

        try (CountingStream postings = new CountingStream(dir.resolve(POSTINGS));
             CountingStream title = new CountingStream(dir.resolve(TITLE));
             CountingStream alias = new CountingStream(dir.resolve(ALIAS));
             DataOutputStream dictionary = new DataOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(dir.resolve(DICTIONARY))))) {
            dictionary.writeInt(terms.size());
            for (byte[] termBytes : terms) {
                String term = new String(termBytes, StandardCharsets.UTF_8);
                VarInt.write(dictionary, termBytes.length);
                dictionary.write(termBytes);

                dictionary.writeLong(postings.count);
                writeMainBlock(postings, index.postings(term), counts, bytes);

                FieldPostingList t = index.titlePostings(term);
                dictionary.writeLong(t == null ? -1 : title.count);
                if (t != null) {
                    writeFieldBlock(title, t);
                    titleInts += 2L * t.size();
                }
                FieldPostingList a = index.aliasPostings(term);
                dictionary.writeLong(a == null ? -1 : alias.count);
                if (a != null) {
                    writeFieldBlock(alias, a);
                    aliasInts += 2L * a.size();
                }
            }
            raw.put("docIds", 4 * counts[0]);
            raw.put("freqs", 4 * counts[1]);
            raw.put("positions", 4 * counts[2]);
            raw.put("title", 4 * titleInts);
            raw.put("alias", 4 * aliasInts);
            compressed.put("docIds", bytes[0]);
            compressed.put("freqs", bytes[1]);
            compressed.put("positions", bytes[2]);
            compressed.put("title", title.count);
            compressed.put("alias", alias.count);
        }

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(dir.resolve(DOCS))))) {
            int n = docs.size();
            out.writeInt(n);
            for (int d = 0; d < n; d++) {
                out.writeInt(docs.wikiId(d));
            }
            for (int d = 0; d < n; d++) {
                out.writeInt(docs.titleLength(d));
            }
            for (int d = 0; d < n; d++) {
                out.writeInt(docs.bodyLength(d));
            }
            for (int d = 0; d < n; d++) {
                out.writeInt(docs.aliasLength(d));
            }
            for (long offset : textOffsets) {   // n + 1 offsets: text of doc d is [offset[d], offset[d+1])
                out.writeLong(offset);
            }
        }
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(dir.resolve(TITLES)))) {
            for (int d = 0; d < docs.size(); d++) {
                byte[] t = docs.title(d).getBytes(StandardCharsets.UTF_8);
                VarInt.write(out, t.length);
                out.write(t);
            }
        }

        // PageRank: one 8-byte number per document (all 0 if PageRank was not computed).
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(dir.resolve(PAGERANK))))) {
            for (int d = 0; d < docs.size(); d++) {
                out.writeDouble(docs.pageRank(d));
            }
        }
        // Spelling vocabulary: count, then per word its length, its bytes and its document count.
        Vocabulary vocabulary = index.vocabulary();
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(dir.resolve(WORDS))))) {
            out.writeInt(vocabulary.size());
            for (int i = 0; i < vocabulary.size(); i++) {
                byte[] w = vocabulary.word(i).getBytes(StandardCharsets.UTF_8);
                VarInt.write(out, w.length);
                out.write(w);
                VarInt.write(out, vocabulary.documentCount(i));
            }
        }

        Map<String, Long> sizes = new LinkedHashMap<>();
        for (String name : List.of(DICTIONARY, POSTINGS, TITLE, ALIAS, DOCS, TITLES, TEXT, PAGERANK, WORDS)) {
            sizes.put(name, Files.size(dir.resolve(name)));
        }
        IndexMeta meta = new IndexMeta(IndexMeta.FORMAT_VERSION, docs.size(), terms.size(), withAliases,
                sizes, raw, compressed);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(dir.resolve(META).toFile(), meta);
        return meta;
    }

    /** Every this many postings, the block stores where that posting's positions start (a "skip" entry). */
    static final int SKIP_EVERY = 64;

    /**
     * Block of one term:
     * <pre>
     *   df | df doc-id gaps | df freqs | skip table | position gaps of every document
     * </pre>
     * The skip table lets a reader jump close to one document's positions instead of decoding the positions
     * of all documents before it: entry k is the number of position bytes between posting (k−1)×64 and
     * posting k×64. The positions are first written to a memory buffer so the skip table can go in front.
     */
    private static void writeMainBlock(CountingStream out, PostingList list, long[] counts, long[] bytes)
            throws IOException {
        int df = list.size();
        bytes[0] += VarInt.write(out, df);
        int previousDoc = 0;
        for (int i = 0; i < df; i++) {
            bytes[0] += VarInt.write(out, list.docId(i) - previousDoc);
            previousDoc = list.docId(i);
        }
        for (int i = 0; i < df; i++) {
            bytes[1] += VarInt.write(out, list.freq(i));
        }

        ByteArrayOutputStream positionBytes = new ByteArrayOutputStream();
        int[] flat = list.flatPositions();   // every document's positions, one after another
        int next = 0;                        // index of the next unread position in flat
        int blockStart = 0;   // size of positionBytes when the current block of 64 postings began
        for (int i = 0; i < df; i++) {
            if (i > 0 && i % SKIP_EVERY == 0) {
                bytes[2] += VarInt.write(out, positionBytes.size() - blockStart);   // one skip entry
                blockStart = positionBytes.size();
            }
            int previous = 0;
            for (int j = 0; j < list.freq(i); j++) {
                int position = flat[next++];
                VarInt.write(positionBytes, position - previous);   // the first gap is the position itself
                previous = position;
            }
        }
        positionBytes.writeTo(out);
        bytes[2] += positionBytes.size();
        counts[0] += df;
        counts[1] += df;
        counts[2] += next;
    }

    /** Block of one term in the title or alias file: n, then n doc-id gaps, then n freqs. */
    private static void writeFieldBlock(CountingStream out, FieldPostingList list) throws IOException {
        VarInt.write(out, list.size());
        int previousDoc = 0;
        for (int i = 0; i < list.size(); i++) {
            VarInt.write(out, list.docId(i) - previousDoc);
            previousDoc = list.docId(i);
        }
        for (int i = 0; i < list.size(); i++) {
            VarInt.write(out, list.freq(i));
        }
    }

    @Override
    public void close() throws IOException {
        text.close();
    }

    /** The in-memory index plus what was written for it ({@code meta} is null if nothing was written). */
    public record Built(InvertedIndex index, IndexMeta meta) {
    }

    /** PageRank settings from the project plan: damping 0.85, stop below 1e-6 total change or after 50 rounds. */
    public static PageRank.Result pageRank(Path articles, RedirectAliases redirects) throws IOException {
        return PageRank.compute(LinkGraph.fromArticles(articles, redirects), 0.85, 1e-6, 50);
    }

    /**
     * Reads {@code articles.jsonl} and builds the index in memory; with {@code dir}, also writes it there.
     *
     * @param aliases  redirect aliases to index, or null for none (the default, see Ranking.DEFAULT)
     * @param pageRank PageRank per document (from {@link #pageRank}), or null for none
     * @param dir      index folder to write, or null to only build in memory
     */
    public static Built build(Path articles, RedirectAliases aliases, double[] pageRank, Path dir)
            throws IOException {
        IndexBuilder builder = new IndexBuilder();
        builder.setPageRank(pageRank);
        IndexFileWriter writer = dir == null ? null : new IndexFileWriter(dir);
        try {
            IOException[] failure = new IOException[1];
            ArticleReader.forEach(articles, a -> {
                builder.addDocument(a.id(), a.title(), a.text(),
                        aliases == null ? List.of() : aliases.aliasesOf(a.title()));
                try {
                    if (writer != null) {
                        writer.appendText(a.text());
                    }
                } catch (IOException e) {
                    failure[0] = e;   // a lambda cannot throw a checked exception; rethrown below
                }
            });
            if (failure[0] != null) {
                throw failure[0];
            }
            InvertedIndex index = builder.build();
            return new Built(index, writer == null ? null : writer.write(index, aliases != null));
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    /** A buffered file stream that remembers how many bytes were written (= the current file offset). */
    private static final class CountingStream extends OutputStream {
        private final OutputStream out;
        private long count;

        CountingStream(Path file) throws IOException {
            this.out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }
}
