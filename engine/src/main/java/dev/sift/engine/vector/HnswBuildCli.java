package dev.sift.engine.vector;

import dev.sift.engine.eval.RunInfo;
import dev.sift.engine.store.DiskIndex;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Builds the HNSW graph over the float32 vectors and writes the int8 copy of the vectors.
 *
 * <pre>scripts/sift-run vector.HnswBuildCli data/index data/vectors [--m 16] [--efc 200] [--seed 42]
 *        [--report eval/results/hnsw-build.txt]</pre>
 */
public final class HnswBuildCli {

    private static final double MB = 1024.0 * 1024.0;

    private HnswBuildCli() {
    }

    public static void main(String[] args) throws IOException {
        Path indexDir = Path.of(args[0]);
        Path vectorDir = Path.of(args[1]);
        int m = 16;
        int efc = 200;
        long seed = 42;
        Path report = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--m" -> m = Integer.parseInt(args[++i]);
                case "--efc" -> efc = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--report" -> report = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        DiskIndex index = DiskIndex.open(indexDir);
        VectorStore vectors = VectorFiles.open(vectorDir, index.docs(), "float32");
        StringBuilder sb = new StringBuilder("# HNSW build\n").append(RunInfo.header());
        sb.append(String.format(Locale.US, "Vectors: %,d × %d float32 = %,.1f MB%n", vectors.size(), vectors.dims(),
                vectors.bytes() / MB));
        System.out.print(sb);

        long heapBefore = usedHeapAfterGc();
        long t0 = System.nanoTime();
        Hnsw graph = Hnsw.build(vectors, m, efc, seed, n -> {
            if (n % 50_000 == 0) {
                System.out.printf(Locale.US, "  %,d nodes inserted, %.0f s%n", n, (System.nanoTime() - t0) / 1e9);
            }
        });
        double buildSeconds = (System.nanoTime() - t0) / 1e9;
        long heapAfter = usedHeapAfterGc();
        Path graphFile = vectorDir.resolve(VectorFiles.GRAPH);
        graph.save(graphFile);

        String layers = "";
        for (int l = 0; l <= graph.maxLevel(); l++) {
            layers += String.format(Locale.US, "%s%d: %,d", l == 0 ? "" : ", ", l, graph.nodesAtLevel(l));
        }
        String lines = String.format(Locale.US, """
                Settings: M = %d (layer 0: %d), efConstruction = %d, seed = %d, single thread, insert order = doc id
                Build time: %.1f s (%.0f inserts/s)
                Layers (nodes at each level or above): %s
                Average links per node on layer 0: %.2f
                Graph in memory: %,.1f MB (arrays); heap after GC grew by %,.1f MB during the build
                Graph file %s: %,.1f MB
                """, m, 2 * m, efc, seed, buildSeconds, vectors.size() / buildSeconds, layers,
                graph.averageDegree(), graph.graphBytes() / MB, (heapAfter - heapBefore) / MB, graphFile,
                Files.size(graphFile) / MB);

        long q0 = System.nanoTime();
        Int8Vectors int8 = Int8Vectors.quantise(vectors);
        Path int8File = vectorDir.resolve(VectorFiles.INT8);
        int8.write(int8File);
        lines += String.format(Locale.US, "int8 vectors %s: %,.1f MB (float32: %,.1f MB), quantised in %.1f s%n",
                int8File, Files.size(int8File) / MB, vectors.bytes() / MB, (System.nanoTime() - q0) / 1e9);
        System.out.print(lines);
        if (report != null) {
            Files.writeString(report, sb + lines);
        }
    }

    private static long usedHeapAfterGc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
