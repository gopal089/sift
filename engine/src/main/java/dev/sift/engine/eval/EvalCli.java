package dev.sift.engine.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sift.engine.data.RedirectAliases;
import dev.sift.engine.index.InvertedIndex;
import dev.sift.engine.rank.PageRank;
import dev.sift.engine.search.Ranking;
import dev.sift.engine.store.DiskIndex;
import dev.sift.engine.store.IndexFileWriter;
import dev.sift.engine.search.Searcher;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Measures ranking quality and speed for one or more ranking settings ("configs") on the frozen queries.
 *
 * <pre>
 *   scripts/sift-eval grid-bm25f --config base=bm25 --config w2=bm25f:w=2,bb=0.75 ...
 * </pre>
 * A config is {@code NAME=SPEC} with a {@link Ranking#parse} spec. Configs with an alias weight ({@code wa} &gt; 0)
 * use an index built with redirect aliases (needs {@code --redirects}); the others use an index without them.
 * With {@code --disk DIR} every index is also written to disk and loaded back, the eval runs on both, and the
 * run fails unless every query gets exactly the same rank and top result from disk as from memory.
 * Writes {@code <out-dir>/<label>.txt} (comparison) and {@code <label>.tsv} (one row per config and query).
 */
public final class EvalCli {

    private static final double MB = 1024.0 * 1024.0;

    /** One search setting to evaluate. It needs the alias index if its alias weight is above 0. */
    record Config(String name, Ranking ranking) {
        boolean usesAliases() {
            return ranking.wAlias() > 0;
        }
    }

    /** One built index and what it cost. */
    record BuiltIndex(InvertedIndex index, double buildSeconds, double heapMb, DiskIndex disk) {
    }

    private EvalCli() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: EvalCli <articles.jsonl> <queries.json> --label NAME --config NAME=SPEC..."
                    + " [--redirects redirects.jsonl] [--disk DIR] [--repeat N] [--out-dir DIR] [--commit HASH]"
                    + " [--machine TEXT]");
            System.exit(2);
        }
        Path articles = Path.of(args[0]);
        Path queriesPath = Path.of(args[1]);
        String label = null;
        Path outDir = Path.of("eval/results");
        Path redirects = null;
        Path diskDir = null;
        String commit = "unknown";
        String machine = System.getProperty("os.arch");
        int repeat = 20;
        List<Config> configs = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--label" -> label = args[++i];
                case "--out-dir" -> outDir = Path.of(args[++i]);
                case "--redirects" -> redirects = Path.of(args[++i]);
                case "--disk" -> diskDir = Path.of(args[++i]);
                case "--commit" -> commit = args[++i];
                case "--machine" -> machine = args[++i];
                case "--repeat" -> repeat = Integer.parseInt(args[++i]);
                case "--config" -> configs.add(parseConfig(args[++i]));
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (label == null || !label.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("--label is required (letters, digits, . _ -)");
        }
        if (configs.isEmpty()) {
            configs.add(new Config("base", Ranking.BASELINE));
        }

        byte[] queryBytes = Files.readAllBytes(queriesPath);
        List<EvalQuery> queries = Arrays.asList(new ObjectMapper().readValue(queryBytes, EvalQuery[].class));

        RedirectAliases aliasTable = redirects == null ? RedirectAliases.of(List.of()) : RedirectAliases.load(redirects);
        if (redirects == null && configs.stream().anyMatch(Config::usesAliases)) {
            throw new IllegalArgumentException("a config uses aliases (wa > 0), so --redirects is required");
        }
        // PageRank once (links resolved through redirects); every index gets the same scores.
        long p0 = System.nanoTime();
        PageRank.Result pageRank = IndexFileWriter.pageRank(articles, aliasTable);
        double pageRankSeconds = (System.nanoTime() - p0) / 1e9;

        // Build each needed index once: key false = without aliases, true = with aliases.
        Map<Boolean, BuiltIndex> indexes = new LinkedHashMap<>();
        for (Config c : configs) {
            if (!indexes.containsKey(c.usesAliases())) {
                indexes.put(c.usesAliases(), build(articles, c.usesAliases() ? aliasTable : null, pageRank.ranks(),
                        diskDir == null ? null : diskDir.resolve(c.usesAliases() ? "with-aliases" : "no-aliases")));
            }
        }

        List<List<Evaluator.Outcome>> outcomes = new ArrayList<>();
        List<double[]> medianMs = new ArrayList<>();
        StringBuilder diskCheck = new StringBuilder();
        for (Config c : configs) {
            BuiltIndex built = indexes.get(c.usesAliases());
            InvertedIndex index = built.index();
            Searcher searcher = new Searcher(index, c.ranking());
            List<Evaluator.Outcome> inMemory = new Evaluator(index.docs()).run(searcher, queries);
            outcomes.add(inMemory);
            if (built.disk() == null) {
                medianMs.add(timeQueries(searcher, queries, repeat));
            } else {
                // Same queries on the index loaded from disk: every rank and top result must be identical.
                Searcher diskSearcher = new Searcher(built.disk(), c.ranking());
                List<Evaluator.Outcome> fromDisk = new Evaluator(built.disk().docs()).run(diskSearcher, queries);
                if (!fromDisk.equals(inMemory)) {
                    for (int q = 0; q < queries.size(); q++) {
                        if (!fromDisk.get(q).equals(inMemory.get(q))) {
                            System.err.println("DISK != MEMORY for config " + c.name() + ":\n  memory "
                                    + inMemory.get(q) + "\n  disk   " + fromDisk.get(q));
                        }
                    }
                    System.exit(1);
                }
                diskCheck.append(String.format(Locale.US,
                        "# disk check %-8s: index loaded from disk gives the same rank, match count and top result"
                                + " as in memory for all %d queries (MRR %.6f = %.6f)%n",
                        c.name(), queries.size(), Evaluator.summarizeByCategory(inMemory).get("ALL").mrr(),
                        Evaluator.summarizeByCategory(fromDisk).get("ALL").mrr()));
                medianMs.add(timeQueries(diskSearcher, queries, repeat));   // speed of the disk index
            }
        }

        StringBuilder header = new StringBuilder();
        header.append(String.format(Locale.US,
                "# Sift eval: %s%n"
                        + "# %s | commit %s | Java %s | %s%n"
                        + "# queries: %s (%d queries, sha256 %s)%n"
                        + "# articles: %s%s%n",
                label,
                ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")),
                commit, Runtime.version(), machine,
                queriesPath, queries.size(), sha256(queryBytes),
                articles, redirects == null ? "" : " | redirects: " + redirects + " (" + aliasTable.size()
                        + " resolved)"));
        header.append(String.format(Locale.US, "# pagerank: d=0.85, %d rounds, last change %.2e, %.1f s%n",
                pageRank.rounds(), pageRank.change(), pageRankSeconds));
        for (Map.Entry<Boolean, BuiltIndex> e : indexes.entrySet()) {
            InvertedIndex idx = e.getValue().index();
            header.append(String.format(Locale.US,
                    "# index (%s aliases): %,d docs, built in %.2f s, heap %.1f MB; avg title %.2f tokens,"
                            + " avg body %.1f tokens; title postings %,d; alias postings %,d%n",
                    e.getKey() ? "with" : "no", idx.docs().size(), e.getValue().buildSeconds(),
                    e.getValue().heapMb(), idx.docs().averageTitleLength(), idx.docs().averageBodyLength(),
                    idx.titlePostingCount(), idx.aliasPostingCount()));
        }
        header.append(diskCheck);
        header.append(String.format(Locale.US, "# speed (%s): k = 10, per query the median of %d runs after 3 warm-up runs%n",
                diskDir == null ? "in-memory index" : "index loaded from disk", repeat));

        String text = header + EvalReport.comparison(configs, outcomes, medianMs);
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve(label + ".txt"), text);
        Files.writeString(outDir.resolve(label + ".tsv"), EvalReport.tsv(configs, outcomes, medianMs));
        System.out.print(text);
        System.out.println("Wrote " + outDir.resolve(label + ".txt") + " and " + outDir.resolve(label + ".tsv"));
    }

    /** {@code NAME=SPEC}, e.g. {@code "w2=bm25sum:w=2,bb=0.3,wa=1"}. */
    static Config parseConfig(String arg) {
        String[] nameSpec = arg.split("=", 2);
        if (nameSpec.length != 2 || !nameSpec[0].matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("--config needs NAME=SPEC, got \"" + arg + "\"");
        }
        return new Config(nameSpec[0], Ranking.parse(nameSpec[1]));
    }

    /**
     * Builds the index; {@code aliasTable} null means "without aliases". With {@code diskDir}, the index is
     * also written there and loaded back as a {@link DiskIndex}.
     */
    private static BuiltIndex build(Path articles, RedirectAliases aliasTable, double[] pageRank, Path diskDir)
            throws IOException {
        long heapBefore = usedHeapAfterGc();
        long t0 = System.nanoTime();
        InvertedIndex index = IndexFileWriter.build(articles, aliasTable, pageRank, diskDir).index();
        double seconds = (System.nanoTime() - t0) / 1e9;
        double heapMb = (usedHeapAfterGc() - heapBefore) / MB;
        return new BuiltIndex(index, seconds, heapMb, diskDir == null ? null : DiskIndex.open(diskDir));
    }

    /** Per query: 3 warm-up runs, then the median of {@code repeat} timed runs with K = 10, in milliseconds. */
    private static double[] timeQueries(Searcher searcher, List<EvalQuery> queries, int repeat) {
        double[] medians = new double[queries.size()];
        for (int q = 0; q < queries.size(); q++) {
            String text = queries.get(q).query();
            for (int w = 0; w < 3; w++) {
                searcher.search(text, 10);
            }
            double[] runs = new double[repeat];
            for (int r = 0; r < repeat; r++) {
                long s = System.nanoTime();
                searcher.search(text, 10);
                runs[r] = (System.nanoTime() - s) / 1e6;
            }
            Arrays.sort(runs);
            medians[q] = runs[repeat / 2];
        }
        return medians;
    }

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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
