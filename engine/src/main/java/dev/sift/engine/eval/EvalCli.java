package dev.sift.engine.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sift.engine.data.ArticleReader;
import dev.sift.engine.index.IndexBuilder;
import dev.sift.engine.index.InvertedIndex;
import dev.sift.engine.search.Searcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Measures ranking quality: builds the index, runs {@code eval/queries.json}, and writes
 * {@code <out-dir>/<label>.txt} (human-readable) and {@code <label>.tsv} (one row per query, for diffs).
 *
 * <pre>
 *   scripts/sift-eval baseline-bm25        (the script passes the paths, commit and machine)
 * </pre>
 */
public final class EvalCli {

    private EvalCli() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: EvalCli <articles.jsonl> <queries.json> --label NAME"
                    + " [--out-dir DIR] [--commit HASH] [--machine TEXT]");
            System.exit(2);
        }
        Path articles = Path.of(args[0]);
        Path queriesPath = Path.of(args[1]);
        String label = null;
        Path outDir = Path.of("eval/results");
        String commit = "unknown";
        String machine = System.getProperty("os.arch");
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--label" -> label = args[++i];
                case "--out-dir" -> outDir = Path.of(args[++i]);
                case "--commit" -> commit = args[++i];
                case "--machine" -> machine = args[++i];
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (label == null || !label.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("--label is required (letters, digits, . _ -)");
        }

        byte[] queryBytes = Files.readAllBytes(queriesPath);
        List<EvalQuery> queries = Arrays.asList(new ObjectMapper().readValue(queryBytes, EvalQuery[].class));

        long t0 = System.nanoTime();
        IndexBuilder builder = new IndexBuilder();
        int docCount = ArticleReader.forEach(articles, a -> builder.addDocument(a.id(), a.title(), a.text()));
        InvertedIndex index = builder.build();
        double buildSeconds = (System.nanoTime() - t0) / 1e9;

        Searcher searcher = new Searcher(index);
        Evaluator evaluator = new Evaluator(index.docs());
        List<Evaluator.Outcome> outcomes = evaluator.run(searcher, queries);

        String header = String.format(Locale.US,
                "# Sift eval: %s%n"
                        + "# %s | commit %s | Java %s | %s%n"
                        + "# queries: %s (%d queries, sha256 %s)%n"
                        + "# articles: %s (%,d documents, index built in %.2f s)%n"
                        + "# ranking: %s%n",
                label,
                ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")),
                commit, Runtime.version(), machine,
                queriesPath, queries.size(), sha256(queryBytes),
                articles, docCount, buildSeconds,
                searcher.description());

        String text = header + EvalReport.text(outcomes);
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve(label + ".txt"), text);
        Files.writeString(outDir.resolve(label + ".tsv"), EvalReport.tsv(outcomes));
        System.out.print(text);
        System.out.println("Wrote " + outDir.resolve(label + ".txt") + " and " + outDir.resolve(label + ".tsv"));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
