package dev.sift.engine.cli;

import dev.sift.engine.data.ArticleReader;
import dev.sift.engine.index.IndexBuilder;
import dev.sift.engine.index.InvertedIndex;
import dev.sift.engine.search.SearchResult;
import dev.sift.engine.search.SearchResult.Hit;
import dev.sift.engine.search.Searcher;
import dev.sift.engine.text.StopWords;
import dev.sift.engine.text.Tokenizer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Terminal search over articles.jsonl.
 *
 * <pre>
 *   sift-cli data/articles-sample.jsonl                                   interactive: type queries
 *   sift-cli data/articles.jsonl --query "photosynthesis" --repeat 20     scripted: timings + top 10
 * </pre>
 */
public final class SearchCli {

    private static final double MB = 1024.0 * 1024.0;

    private SearchCli() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: sift-cli <articles.jsonl> [--query \"text\"]... [--repeat N] [--top K]");
            System.exit(2);
        }
        Path path = Path.of(args[0]);
        List<String> queries = new ArrayList<>();
        int repeat = 20;
        int top = 10;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--query" -> queries.add(args[++i]);
                case "--repeat" -> repeat = Integer.parseInt(args[++i]);
                case "--top" -> top = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (!Files.isReadable(path)) {
            System.err.println("cannot read " + path);
            System.exit(1);
        }

        InvertedIndex index = buildAndReport(path);
        Searcher searcher = new Searcher(index);

        if (queries.isEmpty()) {
            interactive(searcher, top);
        } else {
            for (String q : queries) {
                timed(searcher, q, top, repeat);
            }
        }
    }

    // ------------------------------------------------------------------ build

    private static InvertedIndex buildAndReport(Path path) throws IOException {
        long heapBefore = usedHeapAfterGc();
        System.out.printf(Locale.US, "Java %s, max heap %,.0f MB%n",
                Runtime.version(), Runtime.getRuntime().maxMemory() / MB);
        if (Runtime.version().feature() != 21) {
            // Official numbers must come from Java 21; other versions can be faster or slower.
            System.out.println("WARNING: not Java 21. Use scripts/sift-cli to run on Java 21.");
        }
        System.out.println("Indexing " + path + " ...");

        long t0 = System.nanoTime();
        IndexBuilder builder = new IndexBuilder();
        int count = ArticleReader.forEach(path, a -> builder.addDocument(a.id(), a.title(), a.text()));
        long t1 = System.nanoTime();
        InvertedIndex index = builder.build();
        long t2 = System.nanoTime();

        long heapAfter = usedHeapAfterGc();

        long postings = index.postingCount();
        long positions = index.positionCount();
        long dataBytes = index.postingDataBytes();

        // What keeping stop words in the index costs: the posting data of their terms.
        Set<String> stopTerms = new TreeSet<>();
        for (String word : StopWords.ENGLISH) {
            stopTerms.addAll(Tokenizer.tokenize(word));
        }
        long stopBytes = index.postingDataBytes(stopTerms);
        long stopPositions = 0;
        long stopPostings = 0;
        for (String t : stopTerms) {
            if (index.postings(t) != null) {
                stopPositions += index.postings(t).positionCount();
                stopPostings += index.postings(t).size();
            }
        }

        System.out.printf(Locale.US, "Documents:            %,d%n", count);
        System.out.printf(Locale.US, "Build time:           %.2f s  (read + tokenize + index %.2f s, finalize %.2f s)%n",
                (t2 - t0) / 1e9, (t1 - t0) / 1e9, (t2 - t1) / 1e9);
        System.out.printf(Locale.US, "Unique terms:         %,d%n", index.termCount());
        System.out.printf(Locale.US, "Postings:             %,d   Positions (= tokens): %,d   Avg doc length: %.1f tokens%n",
                postings, positions, index.docs().averageLength());
        System.out.printf(Locale.US, "Posting data (exact): %,.1f MB  (doc ids + freqs + positions, 4 bytes each)%n",
                dataBytes / MB);
        System.out.printf(Locale.US, "Heap used by index:   %,.1f MB  (JVM heap after GC: %,.1f MB before → %,.1f MB after;"
                + " includes dictionary, titles, doc table)%n",
                (heapAfter - heapBefore) / MB, heapBefore / MB, heapAfter / MB);
        System.out.printf(Locale.US, "Stop words (%d terms): %,d postings, %,d positions = %,.1f MB of posting data"
                + " (%.1f%% of posting data, %.1f%% of all tokens)%n",
                stopTerms.size(), stopPostings, stopPositions, stopBytes / MB,
                100.0 * stopBytes / dataBytes, 100.0 * stopPositions / positions);
        System.out.println();
        return index;
    }

    /** Asks the JVM to collect garbage a few times, then reads how much heap is in use. */
    private static long usedHeapAfterGc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    // ------------------------------------------------------------------ queries

    private static void timed(Searcher searcher, String query, int top, int repeat) {
        long start = System.nanoTime();
        SearchResult result = searcher.search(query, top);
        double firstMs = (System.nanoTime() - start) / 1e6;

        double[] runs = new double[repeat];
        for (int i = 0; i < repeat; i++) {
            long s = System.nanoTime();
            searcher.search(query, top);
            runs[i] = (System.nanoTime() - s) / 1e6;
        }
        Arrays.sort(runs);

        System.out.printf(Locale.US, "Query: \"%s\"   terms=%s   mode=%s   matches=%,d%n",
                query, result.terms(), result.mode(), result.totalMatches());
        if (repeat > 0) {
            System.out.printf(Locale.US, "  time: first run %.2f ms | median of %d repeats %.2f ms | slowest repeat %.2f ms%n",
                    firstMs, repeat, runs[repeat / 2], runs[repeat - 1]);
        } else {
            System.out.printf(Locale.US, "  time: %.2f ms%n", firstMs);
        }
        printHits(result);
        System.out.println();
    }

    private static void interactive(Searcher searcher, int top) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.println("Type a query (empty line or Ctrl-D to quit).");
        while (true) {
            System.out.print("sift> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null || line.isBlank()) {
                return;
            }
            timed(searcher, line.strip(), top, 0);
        }
    }

    private static void printHits(SearchResult result) {
        if (result.hits().isEmpty()) {
            System.out.println("  (no results)");
        }
        int rank = 1;
        for (Hit hit : result.hits()) {
            System.out.printf(Locale.US, "  %2d. %8.4f  %s%n", rank++, hit.score(), hit.title());
        }
    }
}
