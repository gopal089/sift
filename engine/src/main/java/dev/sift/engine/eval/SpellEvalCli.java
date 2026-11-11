package dev.sift.engine.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sift.engine.spell.SpellChecker;
import dev.sift.engine.store.DiskIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;

/**
 * Measures the spelling suggestions on the frozen typo queries: how many get exactly the expected
 * correction, and how long a suggestion takes.
 * {@code SpellEvalCli <index-dir> <queries-typo.json> <output.txt> [commit] [machine]}
 */
public final class SpellEvalCli {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TypoQuery(int id, String query, String expected, String type) {
    }

    private SpellEvalCli() {
    }

    public static void main(String[] args) throws IOException {
        Path indexDir = Path.of(args[0]);
        Path queriesPath = Path.of(args[1]);
        Path output = Path.of(args[2]);
        String commit = args.length > 3 ? args[3] : "unknown";
        String machine = args.length > 4 ? args[4] : System.getProperty("os.arch");

        TypoQuery[] queries = new ObjectMapper().readValue(queriesPath.toFile(), TypoQuery[].class);
        DiskIndex index = DiskIndex.open(indexDir);
        long t0 = System.nanoTime();
        SpellChecker checker = new SpellChecker(index.vocabulary());
        double buildSeconds = (System.nanoTime() - t0) / 1e9;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "# Sift spelling eval%n# %s | commit %s | Java %s | %s%n"
                        + "# queries: %s (%d misspelled queries, frozen before the spelling code was written)%n"
                        + "# vocabulary: %,d words; BK-tree built in %.2f s%n"
                        + "# time: first call, and the median of 21 calls, per query%n%n",
                ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")), commit,
                Runtime.version(), machine, queriesPath, queries.length, checker.wordCount(), buildSeconds));
        sb.append(String.format(Locale.US, "%3s  %-18s %-18s %-18s %-5s %9s %10s %10s  %s%n", "id", "query", "expected",
                "suggested", "ok", "first ms", "median ms", "distances", "type"));

        int correct = 0;
        double slowestFirst = 0;
        double slowestMedian = 0;
        for (TypoQuery q : queries) {
            long s = System.nanoTime();
            String suggestion = checker.correctQuery(q.query());
            double firstMs = (System.nanoTime() - s) / 1e6;
            int distanceCalls = checker.lastSearchDistanceCalls();
            double[] runs = new double[21];
            for (int r = 0; r < runs.length; r++) {
                long rs = System.nanoTime();
                checker.correctQuery(q.query());
                runs[r] = (System.nanoTime() - rs) / 1e6;
            }
            Arrays.sort(runs);
            boolean ok = q.expected().equals(suggestion);
            correct += ok ? 1 : 0;
            slowestFirst = Math.max(slowestFirst, firstMs);
            slowestMedian = Math.max(slowestMedian, runs[runs.length / 2]);
            sb.append(String.format(Locale.US, "%3d  %-18s %-18s %-18s %-5s %9.2f %10.2f %,10d  %s%n", q.id(), q.query(),
                    q.expected(), suggestion == null ? "(none)" : suggestion, ok ? "yes" : "NO", firstMs,
                    runs[runs.length / 2], distanceCalls, q.type()));
        }
        sb.append(String.format(Locale.US, "%nAccuracy: %d / %d correct (%.1f%%)%n", correct, queries.length,
                100.0 * correct / queries.length));
        sb.append(String.format(Locale.US, "Slowest suggestion: %.2f ms on the first call, %.2f ms median%n",
                slowestFirst, slowestMedian));
        sb.append("(\"distances\" = edit distances the BK-tree computed for the last unknown word of the query,"
                + " out of the vocabulary size)\n");
        Files.createDirectories(output.getParent());
        Files.writeString(output, sb.toString());
        System.out.print(sb);
    }
}
