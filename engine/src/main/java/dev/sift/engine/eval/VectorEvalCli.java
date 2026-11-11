package dev.sift.engine.eval;

import dev.sift.engine.embed.QueryEmbedder;
import dev.sift.engine.search.ScoredDoc;
import dev.sift.engine.store.DiskIndex;
import dev.sift.engine.vector.ExactIndex;
import dev.sift.engine.vector.Hnsw;
import dev.sift.engine.vector.HnswIndex;
import dev.sift.engine.vector.SemanticIndex;
import dev.sift.engine.vector.StackedVectors;
import dev.sift.engine.vector.VectorFiles;
import dev.sift.engine.vector.VectorStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * HNSW recall and speed against exact (brute-force) search, and int8 against float32.
 *
 * <p>Queries: 500 random article titles (seed 42), embedded by the Java path. The query vector is a
 * title alone, not the stored "title + 200 words" vector, so it is a real search, though the article
 * whose title it is will often be #1 in both lists.
 *
 * <p>recall@10 = |approximate top 10 ∩ exact float32 top 10| / 10, averaged over the queries.
 *
 * <pre>scripts/sift-run eval.VectorEvalCli data/index data/vectors[,data/vectors-2] data/model OUT.txt
 *        [--graph data/vectors/hnsw.bin]</pre>
 * With two vector folders, a document's similarity is the max over its two vectors (exact and HNSW alike).
 */
public final class VectorEvalCli {

    static final int QUERIES = 500;
    static final long SEED = 42;
    static final int K = 10;
    static final int[] EF_SEARCH = {50, 100, 200};
    static final double MIN_RECALL = 0.95;        // rule for choosing efSearch
    static final double MIN_INT8_RECALL = 0.97;   // rule (a) for adopting int8

    private VectorEvalCli() {
    }

    interface Method {
        List<ScoredDoc> search(float[] q);
    }

    record Measured(double recall, double medianMs, double p95Ms) {
    }

    public static void main(String[] args) throws IOException {
        DiskIndex index = DiskIndex.open(Path.of(args[0]));
        String[] dirs = args[1].split(",");
        Path out = Path.of(args[3]);
        Path graphFile = Path.of(dirs[0]).resolve(VectorFiles.GRAPH);
        if (args.length > 5 && args[4].equals("--graph")) {
            graphFile = Path.of(args[5]);
        }
        VectorStore[] f32Parts = new VectorStore[dirs.length];
        VectorStore[] i8Parts = new VectorStore[dirs.length];
        for (int i = 0; i < dirs.length; i++) {
            f32Parts[i] = VectorFiles.open(Path.of(dirs[i]), index.docs(), "float32");
            i8Parts[i] = VectorFiles.open(Path.of(dirs[i]), index.docs(), "int8");
        }
        int n = index.docs().size();
        SemanticIndex exactF32 = new ExactIndex(args[1], f32Parts);
        SemanticIndex exactI8 = new ExactIndex(args[1], i8Parts);
        Hnsw graphF32 = Hnsw.load(graphFile, dirs.length == 1 ? f32Parts[0] : new StackedVectors(f32Parts));
        Hnsw graphI8 = Hnsw.load(graphFile, dirs.length == 1 ? i8Parts[0] : new StackedVectors(i8Parts));

        // ---- the queries
        Random random = new Random(SEED);
        Set<Integer> picked = new LinkedHashSet<>();
        while (picked.size() < QUERIES) {
            picked.add(random.nextInt(index.docs().size()));
        }
        List<float[]> queries = new ArrayList<>();
        double[] embedMs = new double[QUERIES];
        try (QueryEmbedder embedder = QueryEmbedder.open(Path.of(args[2]))) {
            int i = 0;
            for (int doc : picked) {
                long s = System.nanoTime();
                queries.add(embedder.embed(index.docs().title(doc)));
                embedMs[i++] = (System.nanoTime() - s) / 1e6;
            }
        }

        // ---- exact reference
        List<Set<Integer>> exact = new ArrayList<>();
        for (float[] q : queries) {
            Set<Integer> top = new HashSet<>();
            exactF32.top(q, K).forEach(sd -> top.add(sd.docId()));
            exact.add(top);
        }

        StringBuilder sb = new StringBuilder("# Vector search: HNSW vs exact, int8 vs float32\n").append(RunInfo.header());
        sb.append(String.format(Locale.US, "# %d random article titles (seed %d) as queries; recall@%d vs exact float32"
                + " brute force; times = one search per query after a warm-up, single thread%n", QUERIES, SEED, K));
        sb.append(String.format(Locale.US, "# vectors: %s (%d per article); graph %s: M = %d, efConstruction = %d,"
                        + " %,d nodes%n%n", args[1], dirs.length, graphFile, graphF32.m(), graphF32.efConstruction(),
                graphF32.size()));
        sb.append(String.format(Locale.US, "%-34s %10s %10s %10s%n", "method", "recall@10", "median ms", "p95 ms"));

        Measured bruteF32 = measure(queries, exact, q -> exactF32.top(q, K));
        row(sb, "brute force float32 (exact)", bruteF32);
        Measured bruteI8 = measure(queries, exact, q -> exactI8.top(q, K));
        row(sb, "brute force int8", bruteI8);
        Measured[] hnswF32 = new Measured[EF_SEARCH.length];
        Measured[] hnswI8 = new Measured[EF_SEARCH.length];
        for (int e = 0; e < EF_SEARCH.length; e++) {
            int ef = EF_SEARCH[e];
            HnswIndex h = new HnswIndex(args[1], graphF32, ef, n);
            hnswF32[e] = measure(queries, exact, q -> h.top(q, K));
            row(sb, "HNSW float32 efSearch=" + ef, hnswF32[e]);
        }
        for (int e = 0; e < EF_SEARCH.length; e++) {
            int ef = EF_SEARCH[e];
            HnswIndex h = new HnswIndex(args[1], graphI8, ef, n);
            hnswI8[e] = measure(queries, exact, q -> h.top(q, K));
            row(sb, "HNSW int8 efSearch=" + ef, hnswI8[e]);
        }

        // ---- decisions (rules written before measuring: docs/learning/phase-5-plan.md)
        int chosen = -1;
        for (int e = 0; e < EF_SEARCH.length && chosen < 0; e++) {
            if (hnswF32[e].recall() >= MIN_RECALL) {
                chosen = e;
            }
        }
        sb.append('\n');
        if (chosen < 0) {
            sb.append(String.format(Locale.US, "efSearch: NO value reaches recall@10 >= %.2f%n", MIN_RECALL));
        } else {
            sb.append(String.format(Locale.US, "efSearch rule (smallest with recall@10 >= %.2f): efSearch = %d"
                    + " (recall %.3f)%n", MIN_RECALL, EF_SEARCH[chosen], hnswF32[chosen].recall()));
        }
        sb.append(String.format(Locale.US, "int8 rule part (a), quantisation alone: brute-force int8 vs float32"
                        + " recall@10 = %.3f -> %s (needs >= %.2f; part (b), the MRR check, is in the Smart eval)%n",
                bruteI8.recall(), bruteI8.recall() >= MIN_INT8_RECALL ? "PASS" : "FAIL", MIN_INT8_RECALL));
        Arrays.sort(embedMs);
        sb.append(String.format(Locale.US, "Query embedding of the 500 titles: median %.2f ms, p95 %.2f ms%n",
                embedMs[QUERIES / 2], embedMs[(int) Math.ceil(QUERIES * 0.95) - 1]));
        System.out.print(sb);
        Files.writeString(out, sb.toString());
    }

    private static Measured measure(List<float[]> queries, List<Set<Integer>> exact, Method method) {
        for (int i = 0; i < 50; i++) {
            method.search(queries.get(i));                 // warm-up: let the JIT compile the hot loops
        }
        double recall = 0;
        double[] ms = new double[queries.size()];
        for (int i = 0; i < queries.size(); i++) {
            long s = System.nanoTime();
            List<ScoredDoc> got = method.search(queries.get(i));
            ms[i] = (System.nanoTime() - s) / 1e6;
            Set<Integer> truth = exact.get(i);
            recall += got.stream().filter(sd -> truth.contains(sd.docId())).count() / (double) K;
        }
        Arrays.sort(ms);
        return new Measured(recall / queries.size(), ms[ms.length / 2], ms[(int) Math.ceil(ms.length * 0.95) - 1]);
    }

    private static void row(StringBuilder sb, String name, Measured m) {
        sb.append(String.format(Locale.US, "%-34s %10.3f %10.3f %10.3f%n", name, m.recall(), m.medianMs(), m.p95Ms()));
        System.out.print(sb.substring(sb.lastIndexOf("\n", sb.length() - 2) + 1));
    }
}
