package dev.sift.engine.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sift.engine.data.ArticleReader;
import dev.sift.engine.data.RedirectAliases;
import dev.sift.engine.index.IndexBuilder;
import dev.sift.engine.index.IndexBuilder.AliasWords;
import dev.sift.engine.index.InvertedIndex;
import dev.sift.engine.search.Ranking;
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
import java.util.EnumMap;
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
 * A config is {@code NAME=SPEC}; SPEC is a {@link Ranking#parse} spec plus an optional {@code aliases=none|all|new}
 * (redirect titles in the title field; needs {@code --redirects}). Each distinct alias setting builds its own index.
 * Writes {@code <out-dir>/<label>.txt} (comparison) and {@code <label>.tsv} (one row per config and query).
 */
public final class EvalCli {

    private static final double MB = 1024.0 * 1024.0;

    /** Redirect titles in the title field: none, every word, or only new words once each. */
    enum Aliases { NONE, ALL, NEW }

    /** One ranking setting to evaluate. */
    record Config(String name, Ranking ranking, Aliases aliases) {
        String describe() {
            return ranking + " | aliases: " + aliases.name().toLowerCase(Locale.ROOT);
        }
    }

    /** One built index and what it cost. */
    record BuiltIndex(InvertedIndex index, double buildSeconds, double heapMb) {
    }

    private EvalCli() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: EvalCli <articles.jsonl> <queries.json> --label NAME --config NAME=SPEC..."
                    + " [--redirects redirects.jsonl] [--repeat N] [--out-dir DIR] [--commit HASH] [--machine TEXT]");
            System.exit(2);
        }
        Path articles = Path.of(args[0]);
        Path queriesPath = Path.of(args[1]);
        String label = null;
        Path outDir = Path.of("eval/results");
        Path redirects = null;
        String commit = "unknown";
        String machine = System.getProperty("os.arch");
        int repeat = 20;
        List<Config> configs = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--label" -> label = args[++i];
                case "--out-dir" -> outDir = Path.of(args[++i]);
                case "--redirects" -> redirects = Path.of(args[++i]);
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
            configs.add(new Config("base", Ranking.BASELINE, Aliases.NONE));
        }

        byte[] queryBytes = Files.readAllBytes(queriesPath);
        List<EvalQuery> queries = Arrays.asList(new ObjectMapper().readValue(queryBytes, EvalQuery[].class));

        RedirectAliases aliasTable = null;
        if (configs.stream().anyMatch(c -> c.aliases() != Aliases.NONE)) {
            if (redirects == null) {
                throw new IllegalArgumentException("a config uses aliases, so --redirects is required");
            }
            aliasTable = RedirectAliases.load(redirects);
        }

        // Build each needed index once; configs that share an alias setting share the index.
        Map<Aliases, BuiltIndex> indexes = new EnumMap<>(Aliases.class);
        for (Config c : configs) {
            if (!indexes.containsKey(c.aliases())) {
                indexes.put(c.aliases(), build(articles, c.aliases(), aliasTable));
            }
        }

        List<List<Evaluator.Outcome>> outcomes = new ArrayList<>();
        List<double[]> medianMs = new ArrayList<>();
        for (Config c : configs) {
            InvertedIndex index = indexes.get(c.aliases()).index();
            Searcher searcher = new Searcher(index, c.ranking());
            outcomes.add(new Evaluator(index.docs()).run(searcher, queries));
            medianMs.add(timeQueries(searcher, queries, repeat));
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
                articles, aliasTable == null ? "" : " | redirects: " + redirects + " (" + aliasTable.size()
                        + " resolved)"));
        for (Map.Entry<Aliases, BuiltIndex> e : indexes.entrySet()) {
            InvertedIndex idx = e.getValue().index();
            header.append(String.format(Locale.US,
                    "# index (aliases %s): %,d docs, built in %.2f s, heap %.1f MB; avg title field %.2f tokens,"
                            + " avg body %.1f tokens; title-field postings %,d (%.1f MB)%n",
                    e.getKey().name().toLowerCase(Locale.ROOT), idx.docs().size(), e.getValue().buildSeconds(),
                    e.getValue().heapMb(), idx.docs().averageTitleFieldLength(), idx.docs().averageBodyLength(),
                    idx.titlePostingCount(), idx.titlePostingDataBytes() / MB));
        }
        header.append(String.format(Locale.US, "# speed: k = 10, per query the median of %d runs after 3 warm-up runs%n",
                repeat));

        String text = header + EvalReport.comparison(configs, outcomes, medianMs);
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve(label + ".txt"), text);
        Files.writeString(outDir.resolve(label + ".tsv"), EvalReport.tsv(configs, outcomes, medianMs));
        System.out.print(text);
        System.out.println("Wrote " + outDir.resolve(label + ".txt") + " and " + outDir.resolve(label + ".tsv"));
    }

    /** {@code NAME=SPEC}, where SPEC may contain {@code aliases=none|all|new} among its parameters. */
    static Config parseConfig(String arg) {
        String[] nameSpec = arg.split("=", 2);
        if (nameSpec.length != 2 || !nameSpec[0].matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("--config needs NAME=SPEC, got \"" + arg + "\"");
        }
        String spec = nameSpec[1];
        Aliases aliases = Aliases.NONE;
        String[] modelAndParams = spec.split(":", 2);
        List<String> rankingParams = new ArrayList<>();
        if (modelAndParams.length == 2) {
            for (String kv : modelAndParams[1].split(",")) {
                if (kv.strip().startsWith("aliases=")) {
                    aliases = Aliases.valueOf(kv.strip().substring("aliases=".length()).toUpperCase(Locale.ROOT));
                } else if (!kv.isBlank()) {
                    rankingParams.add(kv);
                }
            }
        }
        Ranking ranking = Ranking.parse(modelAndParams[0] + ":" + String.join(",", rankingParams));
        return new Config(nameSpec[0], ranking, aliases);
    }

    private static BuiltIndex build(Path articles, Aliases aliases, RedirectAliases aliasTable) throws IOException {
        long heapBefore = usedHeapAfterGc();
        long t0 = System.nanoTime();
        IndexBuilder builder = new IndexBuilder(aliases == Aliases.NEW ? AliasWords.NEW_ONLY : AliasWords.ALL);
        ArticleReader.forEach(articles, a -> builder.addDocument(a.id(), a.title(), a.text(),
                aliases == Aliases.NONE ? List.of() : aliasTable.aliasesOf(a.title())));
        InvertedIndex index = builder.build();
        double seconds = (System.nanoTime() - t0) / 1e9;
        double heapMb = (usedHeapAfterGc() - heapBefore) / MB;
        return new BuiltIndex(index, seconds, heapMb);
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
