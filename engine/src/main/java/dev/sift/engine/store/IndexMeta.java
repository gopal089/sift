package dev.sift.engine.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * {@code meta.json}: what is in an index folder. Written last, so a folder without it is not a finished index.
 *
 * @param formatVersion  bumped whenever the file layout changes; the reader refuses other versions
 * @param documents      number of documents
 * @param terms          number of unique terms
 * @param withAliases    true if redirect aliases were indexed
 * @param fileSizes      file name → size in bytes; the reader checks them to detect truncated files
 * @param rawBytes       posting data as plain 4-byte ints (the "before compression" size), per part
 * @param compressedBytes the same parts as stored on disk (gap + varint)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IndexMeta(int formatVersion, int documents, int terms, boolean withAliases,
                        Map<String, Long> fileSizes, Map<String, Long> rawBytes, Map<String, Long> compressedBytes) {

    public static final int FORMAT_VERSION = 1;

    public long totalBytes() {
        long total = 0;
        for (long size : fileSizes.values()) {
            total += size;
        }
        return total;
    }
}
