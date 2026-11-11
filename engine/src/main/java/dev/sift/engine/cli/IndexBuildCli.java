package dev.sift.engine.cli;

import dev.sift.engine.data.RedirectAliases;
import dev.sift.engine.store.DiskIndex;
import dev.sift.engine.store.IndexFileWriter;
import dev.sift.engine.store.IndexMeta;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the on-disk index: {@code sift-index <articles.jsonl> <index-dir> [--redirects redirects.jsonl]}.
 * Prints the size of every file and the posting data before vs after compression.
 */
public final class IndexBuildCli {

    private static final double MB = 1024.0 * 1024.0;

    private IndexBuildCli() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: sift-index <articles.jsonl> <index-dir> [--redirects redirects.jsonl]");
            System.exit(2);
        }
        Path articles = Path.of(args[0]);
        Path dir = Path.of(args[1]);
        RedirectAliases aliases = null;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--redirects")) {
                aliases = RedirectAliases.load(Path.of(args[++i]));
            } else {
                throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        System.out.printf(Locale.US, "Java %s%n", Runtime.version());
        long t0 = System.nanoTime();
        IndexMeta meta = IndexFileWriter.buildFromArticles(articles, aliases, dir).meta();
        double buildSeconds = (System.nanoTime() - t0) / 1e9;

        long t1 = System.nanoTime();
        DiskIndex.open(dir);
        double openSeconds = (System.nanoTime() - t1) / 1e9;

        System.out.printf(Locale.US, "Built %s from %s in %.2f s (read, index, write)%n", dir, articles, buildSeconds);
        System.out.printf(Locale.US, "Documents: %,d   Terms: %,d   Aliases indexed: %s%n",
                meta.documents(), meta.terms(), meta.withAliases() ? "yes" : "no");
        System.out.printf(Locale.US, "Opening the index from disk takes %.2f s%n%n", openSeconds);

        System.out.println("Posting data: plain 4-byte ints (before) vs gap + varint (after)");
        System.out.printf(Locale.US, "  %-10s %12s %12s %8s%n", "part", "before MB", "after MB", "after %");
        long rawTotal = 0;
        long compressedTotal = 0;
        for (Map.Entry<String, Long> e : meta.rawBytes().entrySet()) {
            long raw = e.getValue();
            long compressed = meta.compressedBytes().get(e.getKey());
            rawTotal += raw;
            compressedTotal += compressed;
            System.out.printf(Locale.US, "  %-10s %12.1f %12.1f %7.1f%%%n", e.getKey(), raw / MB, compressed / MB,
                    raw == 0 ? 0 : 100.0 * compressed / raw);
        }
        System.out.printf(Locale.US, "  %-10s %12.1f %12.1f %7.1f%%%n%n", "TOTAL", rawTotal / MB, compressedTotal / MB,
                100.0 * compressedTotal / rawTotal);

        System.out.println("Files");
        for (Map.Entry<String, Long> e : meta.fileSizes().entrySet()) {
            System.out.printf(Locale.US, "  %-16s %10.1f MB%n", e.getKey(), e.getValue() / MB);
        }
        System.out.printf(Locale.US, "  %-16s %10.1f MB%n", "TOTAL", meta.totalBytes() / MB);
    }
}
