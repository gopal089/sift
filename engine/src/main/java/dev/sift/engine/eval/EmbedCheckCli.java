package dev.sift.engine.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sift.engine.embed.QueryEmbedder;
import dev.sift.engine.embed.VectorMath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Checks that Java embeds queries exactly like Python: for every check query, cosine(Java vector,
 * Python vector) must be ≥ 0.9999. Also measures how long one query embedding takes.
 *
 * <pre>sift-embed-check data/model data/vectors/check-queries.jsonl [--out eval/results/embed-check.txt]</pre>
 */
public final class EmbedCheckCli {

    static final double MIN_COSINE = 0.9999;
    private static final int ROUNDS = 5;

    private EmbedCheckCli() {
    }

    public static void main(String[] args) throws IOException {
        Path modelDir = Path.of(args[0]);
        Path checkFile = Path.of(args[1]);
        Path out = args.length > 3 && args[2].equals("--out") ? Path.of(args[3]) : null;

        ObjectMapper json = new ObjectMapper();
        List<String> texts = new ArrayList<>();
        List<float[]> pythonVectors = new ArrayList<>();
        for (String line : Files.readAllLines(checkFile, StandardCharsets.UTF_8)) {
            JsonNode node = json.readTree(line);
            texts.add(node.get("text").asText());
            float[] v = new float[node.get("vector").size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) node.get("vector").get(i).asDouble();
            }
            pythonVectors.add(v);
        }

        StringBuilder sb = new StringBuilder();
        try (QueryEmbedder embedder = QueryEmbedder.open(modelDir)) {
            sb.append(String.format(Locale.US, "# Java vs Python query embeddings (%s)%n", embedder.model()));
            sb.append(RunInfo.header());
            sb.append(String.format(Locale.US, "# %d queries from %s; rule: every cosine >= %.4f%n%n", texts.size(),
                    checkFile, MIN_COSINE));

            double min = 1;
            int minAt = 0;
            int failed = 0;
            double[] cosines = new double[texts.size()];
            for (int q = 0; q < texts.size(); q++) {
                cosines[q] = VectorMath.cosine(embedder.embed(texts.get(q)), pythonVectors.get(q));
                if (cosines[q] < min) {
                    min = cosines[q];
                    minAt = q;
                }
                if (cosines[q] < MIN_COSINE) {
                    failed++;
                    sb.append(String.format(Locale.US, "FAIL cosine %.7f  \"%s\"%n", cosines[q], texts.get(q)));
                }
            }
            double[] sorted = cosines.clone();
            Arrays.sort(sorted);
            sb.append(String.format(Locale.US, "Cosine: min %.7f (\"%s\"), median %.7f, failures %d of %d -> %s%n",
                    min, texts.get(minAt), sorted[sorted.length / 2], failed, texts.size(), failed == 0 ? "PASS" : "FAIL"));

            // timing: all queries, ROUNDS times, after a warm-up round (the first calls are slower: JIT, caches)
            for (String t : texts) {
                embedder.embed(t);
            }
            double[] ms = new double[texts.size() * ROUNDS];
            int n = 0;
            for (int r = 0; r < ROUNDS; r++) {
                for (String t : texts) {
                    long s = System.nanoTime();
                    embedder.embed(t);
                    ms[n++] = (System.nanoTime() - s) / 1e6;
                }
            }
            Arrays.sort(ms);
            sb.append(String.format(Locale.US, "Query embedding time (%d runs, 1 ONNX thread): median %.2f ms,"
                            + " p95 %.2f ms, max %.2f ms%n", ms.length, ms[ms.length / 2],
                    ms[(int) Math.ceil(ms.length * 0.95) - 1], ms[ms.length - 1]));
        }
        System.out.print(sb);
        if (out != null) {
            Files.writeString(out, sb.toString());
        }
    }
}
