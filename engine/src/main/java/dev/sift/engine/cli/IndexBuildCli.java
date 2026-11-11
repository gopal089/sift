package dev.sift.engine.cli;

import dev.sift.engine.data.RedirectAliases;
import dev.sift.engine.index.DocTable;
import dev.sift.engine.index.Vocabulary;
import dev.sift.engine.rank.LinkGraph;
import dev.sift.engine.rank.PageRank;
import dev.sift.engine.store.DiskIndex;
import dev.sift.engine.store.IndexFileWriter;
import dev.sift.engine.store.IndexMeta;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the on-disk index:
 * {@code sift-index <articles.jsonl> <index-dir> [--redirects redirects.jsonl] [--aliases]}.
 * Redirects are used to resolve links for PageRank; with {@code --aliases} they are also indexed as aliases.
 * Prints the size of every file and the posting data before vs after compression.
 */
public final class IndexBuildCli {

    private static final double MB = 1024.0 * 1024.0;

    private IndexBuildCli() {
    }

    private static void printTopPages(DocTable docs, int count) {
        Integer[] order = new Integer[docs.size()];
        for (int d = 0; d < order.length; d++) {
            order[d] = d;
        }
        Arrays.sort(order, (a, b) -> Double.compare(docs.pageRank(b), docs.pageRank(a)));
        System.out.println("Top pages by PageRank:");
        for (int i = 0; i < Math.min(count, order.length); i++) {
            System.out.printf(Locale.US, "  %2d. %.6f  %s%n", i + 1, docs.pageRank(order[i]), docs.title(order[i]));
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: sift-index <articles.jsonl> <index-dir> [--redirects redirects.jsonl] [--aliases]");
            System.exit(2);
        }
        Path articles = Path.of(args[0]);
        Path dir = Path.of(args[1]);
        RedirectAliases redirects = RedirectAliases.of(List.of());
        boolean indexAliases = false;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--redirects")) {
                redirects = RedirectAliases.load(Path.of(args[++i]));
            } else if (args[i].equals("--aliases")) {
                indexAliases = true;
            } else {
                throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        System.out.printf(Locale.US, "Java %s%n", Runtime.version());
        long t0 = System.nanoTime();
        LinkGraph graph = LinkGraph.fromArticles(articles, redirects);
        PageRank.Result pageRank = PageRank.compute(graph, 0.85, 1e-6, 50);
        double pageRankSeconds = (System.nanoTime() - t0) / 1e9;

        long t1b = System.nanoTime();
        IndexFileWriter.Built built = IndexFileWriter.build(articles, indexAliases ? redirects : null,
                pageRank.ranks(), dir);
        IndexMeta meta = built.meta();
        double buildSeconds = (System.nanoTime() - t1b) / 1e9;

        long t1 = System.nanoTime();
        DiskIndex.open(dir);
        double openSeconds = (System.nanoTime() - t1) / 1e9;

        System.out.printf(Locale.US, "PageRank: %,d pages, %,d links (%,d pages with no outgoing links),"
                + " %d rounds, last change %.2e, %.2f s (read links twice + iterate)%n",
                graph.pageCount(), graph.linkCount(), graph.danglingCount(), pageRank.rounds(), pageRank.change(),
                pageRankSeconds);
        printTopPages(built.index().docs(), 15);
        System.out.printf(Locale.US, "Built %s from %s in %.2f s (read, index, write)%n", dir, articles, buildSeconds);
        System.out.printf(Locale.US, "Spelling vocabulary: %,d words in at least %d documents%n",
                built.index().vocabulary().size(), Vocabulary.MIN_DOCUMENTS);
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
