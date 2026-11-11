package dev.sift.engine.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sift.engine.index.DocTable;
import dev.sift.engine.index.FieldPostingList;
import dev.sift.engine.index.PostingList;
import dev.sift.engine.index.SearchIndex;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * An index read from the files written by {@link IndexFileWriter}.
 *
 * <p>At start only the small parts are loaded into Java memory: the term dictionary, the doc table and
 * the titles. The big files (postings, article text) are <b>memory-mapped</b>: the operating system makes
 * the file look like one big array and loads only the pages we actually read. A query decodes just the
 * posting blocks of its own terms.
 *
 * <p>Thread safety: nothing changes after {@link #open}. Each read uses its own {@code duplicate()} of a
 * mapped buffer (same bytes, private read position), so many requests can search at the same time.
 */
public final class DiskIndex implements SearchIndex {

    private final IndexMeta meta;
    private final TermDictionary dictionary;
    private final DocTable docs;
    private final long[] textOffsets;
    private final ByteBuffer postings;
    private final ByteBuffer title;
    private final ByteBuffer alias;
    private final ByteBuffer text;

    private DiskIndex(IndexMeta meta, TermDictionary dictionary, DocTable docs, long[] textOffsets,
                      ByteBuffer postings, ByteBuffer title, ByteBuffer alias, ByteBuffer text) {
        this.meta = meta;
        this.dictionary = dictionary;
        this.docs = docs;
        this.textOffsets = textOffsets;
        this.postings = postings;
        this.title = title;
        this.alias = alias;
        this.text = text;
    }

    public static DiskIndex open(Path dir) throws IOException {
        Path metaFile = dir.resolve(IndexFileWriter.META);
        if (!Files.isReadable(metaFile)) {
            throw new IOException("no index in " + dir + " (meta.json is missing; build it with scripts/sift-index)");
        }
        IndexMeta meta = new ObjectMapper().readValue(metaFile.toFile(), IndexMeta.class);
        if (meta.formatVersion() != IndexMeta.FORMAT_VERSION) {
            throw new IOException("index format " + meta.formatVersion() + " in " + dir + ", this code reads format "
                    + IndexMeta.FORMAT_VERSION + ": rebuild the index");
        }
        for (Map.Entry<String, Long> e : meta.fileSizes().entrySet()) {
            long actual = Files.size(dir.resolve(e.getKey()));
            if (actual != e.getValue()) {
                throw new IOException(e.getKey() + " is " + actual + " bytes, meta.json says " + e.getValue()
                        + ": the index is damaged, rebuild it");
            }
        }

        TermDictionary dictionary = TermDictionary.read(
                ByteBuffer.wrap(Files.readAllBytes(dir.resolve(IndexFileWriter.DICTIONARY))));

        ByteBuffer d = ByteBuffer.wrap(Files.readAllBytes(dir.resolve(IndexFileWriter.DOCS)));
        int n = d.getInt();
        int[] wikiIds = readInts(d, n);
        int[] titleLengths = readInts(d, n);
        int[] bodyLengths = readInts(d, n);
        int[] aliasLengths = readInts(d, n);
        long[] textOffsets = new long[n + 1];
        for (int i = 0; i <= n; i++) {
            textOffsets[i] = d.getLong();
        }

        ByteBuffer t = ByteBuffer.wrap(Files.readAllBytes(dir.resolve(IndexFileWriter.TITLES)));
        String[] titles = new String[n];
        for (int i = 0; i < n; i++) {
            byte[] bytes = new byte[VarInt.read(t)];
            t.get(bytes);
            titles[i] = new String(bytes, StandardCharsets.UTF_8);
        }

        return new DiskIndex(meta, dictionary,
                new DocTable(wikiIds, titles, titleLengths, bodyLengths, aliasLengths), textOffsets,
                map(dir.resolve(IndexFileWriter.POSTINGS)), map(dir.resolve(IndexFileWriter.TITLE)),
                map(dir.resolve(IndexFileWriter.ALIAS)), map(dir.resolve(IndexFileWriter.TEXT)));
    }

    private static int[] readInts(ByteBuffer in, int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = in.getInt();
        }
        return out;
    }

    /** Memory-maps a whole file, read-only. The mapping stays valid after the channel is closed. */
    private static ByteBuffer map(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size > Integer.MAX_VALUE) {
                throw new IOException(file + " is larger than 2 GB, the limit of one mapped buffer");
            }
            return size == 0 ? ByteBuffer.allocate(0) : channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
        }
    }

    @Override
    public PostingList postings(String term) {
        int i = dictionary.find(term);
        if (i < 0) {
            return null;
        }
        ByteBuffer in = postings.duplicate();
        in.position((int) dictionary.mainOffset(i));
        int df = VarInt.read(in);
        int[] docIds = readGaps(in, df);
        int[] freqs = new int[df];
        int total = 0;
        for (int k = 0; k < df; k++) {
            freqs[k] = VarInt.read(in);
            total += freqs[k];
        }
        int positionsStart = in.position();
        int positionCount = total;
        // Positions are decoded only if somebody asks for them (snippets), never for ranking.
        return new PostingList(docIds, freqs, () -> readPositions(positionsStart, freqs, positionCount));
    }

    private int[] readPositions(int start, int[] freqs, int count) {
        ByteBuffer in = postings.duplicate();
        in.position(start);
        int[] positions = new int[count];
        int p = 0;
        for (int freq : freqs) {
            int previous = 0;
            for (int j = 0; j < freq; j++) {
                previous += VarInt.read(in);   // undo the gap encoding inside this document
                positions[p++] = previous;
            }
        }
        return positions;
    }

    @Override
    public FieldPostingList titlePostings(String term) {
        int i = dictionary.find(term);
        return i < 0 ? null : fieldPostings(title, dictionary.titleOffset(i));
    }

    @Override
    public FieldPostingList aliasPostings(String term) {
        int i = dictionary.find(term);
        return i < 0 ? null : fieldPostings(alias, dictionary.aliasOffset(i));
    }

    private static FieldPostingList fieldPostings(ByteBuffer file, long offset) {
        if (offset < 0) {
            return null;   // the term is not in this field
        }
        ByteBuffer in = file.duplicate();
        in.position((int) offset);
        int n = VarInt.read(in);
        int[] docIds = readGaps(in, n);
        int[] freqs = new int[n];
        for (int k = 0; k < n; k++) {
            freqs[k] = VarInt.read(in);
        }
        return new FieldPostingList(docIds, freqs);
    }

    /** Reads n gap-encoded sorted numbers: each stored value is the difference to the one before. */
    private static int[] readGaps(ByteBuffer in, int n) {
        int[] values = new int[n];
        int previous = 0;
        for (int k = 0; k < n; k++) {
            previous += VarInt.read(in);
            values[k] = previous;
        }
        return values;
    }

    @Override
    public DocTable docs() {
        return docs;
    }

    /** The article text of a document (for snippets), read from the mapped text file. */
    public String text(int docId) {
        return text(docId, Integer.MAX_VALUE);
    }

    /**
     * At most the first {@code maxBytes} bytes of the article text. A character cut in half at the end
     * shows up as "�" (the replacement character); callers that cut should trim it.
     */
    public String text(int docId, int maxBytes) {
        long start = textOffsets[docId];
        int length = (int) Math.min(textOffsets[docId + 1] - start, maxBytes);
        byte[] bytes = new byte[length];
        text.duplicate().position((int) start).get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public TermDictionary dictionary() {
        return dictionary;
    }

    public IndexMeta meta() {
        return meta;
    }
}
