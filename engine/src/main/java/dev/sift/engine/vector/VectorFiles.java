package dev.sift.engine.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sift.engine.index.DocTable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The files of the vector folder ({@code data/vectors}):
 * <ul>
 *   <li>{@code vectors-meta.json} — written by {@code data-prep/embed_articles.py}</li>
 *   <li>{@code vectors-f32.bin} — float32 vectors (Python)</li>
 *   <li>{@code vectors-i8.bin} — int8 vectors ({@code HnswBuildCli})</li>
 *   <li>{@code hnsw.bin} — the HNSW graph ({@code HnswBuildCli})</li>
 * </ul>
 * Opening checks that the vectors belong to <i>this</i> index: same number of documents and the same titles
 * in the same order (a SHA-256 hash of all titles). A stale file would otherwise give wrong results quietly.
 */
public final class VectorFiles {

    public static final String META = "vectors-meta.json";
    public static final String FLOAT32 = "vectors-f32.bin";
    public static final String INT8 = "vectors-i8.bin";
    public static final String GRAPH = "hnsw.bin";

    public record Meta(int count, int dims, String model, String titlesSha256) {
    }

    private VectorFiles() {
    }

    public static Meta readMeta(Path dir) throws IOException {
        Path file = dir.resolve(META);
        if (!Files.isReadable(file)) {
            throw new IOException("missing " + file + " — run data-prep/embed_articles.py first (see PROGRESS.md)");
        }
        JsonNode n = new ObjectMapper().readTree(file.toFile());
        return new Meta(n.get("count").asInt(), n.get("dimensions").asInt(), n.get("model").asText(),
                n.get("titles_sha256").asText());
    }

    /** Throws if the vectors were made for a different set of articles than {@code docs}. */
    public static Meta checkMatches(Path dir, DocTable docs) throws IOException {
        Meta meta = readMeta(dir);
        if (meta.count() != docs.size()) {
            throw new IOException("vectors are for " + meta.count() + " articles, the index has " + docs.size()
                    + " — re-run the embedding for the current articles.jsonl");
        }
        String hash = titlesSha256(docs);
        if (!hash.equals(meta.titlesSha256())) {
            throw new IOException("vectors were made for different titles (hash " + meta.titlesSha256()
                    + ", index " + hash + ") — re-run the embedding for the current articles.jsonl");
        }
        return meta;
    }

    /** SHA-256 of all titles joined with "\n", in doc id order (the same recipe as the Python script). */
    public static String titlesSha256(DocTable docs) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (int d = 0; d < docs.size(); d++) {
                if (d > 0) {
                    md.update((byte) '\n');
                }
                md.update(docs.title(d).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Opens the vectors of the given kind ("float32" or "int8") after checking they match {@code docs}. */
    public static VectorStore open(Path dir, DocTable docs, String kind) throws IOException {
        Meta meta = checkMatches(dir, docs);
        return switch (kind) {
            case "float32" -> Float32Vectors.open(dir.resolve(FLOAT32), meta.dims());
            case "int8" -> {
                Path file = dir.resolve(INT8);
                if (!Files.isReadable(file)) {
                    throw new IOException("missing " + file + " — run scripts/sift-run vector.HnswBuildCli first");
                }
                yield Int8Vectors.open(file, meta.count(), meta.dims());
            }
            default -> throw new IllegalArgumentException("vector kind must be float32 or int8, got " + kind);
        };
    }

    /** Opens the vectors and the saved HNSW graph on top of them. */
    public static Hnsw openGraph(Path dir, VectorStore vectors) throws IOException {
        Path file = dir.resolve(GRAPH);
        if (!Files.isReadable(file)) {
            throw new IOException("missing " + file + " — run scripts/sift-run vector.HnswBuildCli first");
        }
        return Hnsw.load(file, vectors);
    }
}
