package dev.sift.engine.vector;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sift.engine.search.ScoredDoc;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VectorSearchTest {

    /** n random vectors of length 1, in clusters (like real embeddings, which are not uniformly spread). */
    static float[] randomUnitVectors(int n, int dims, long seed) {
        Random r = new Random(seed);
        float[][] centres = new float[20][dims];
        for (float[] c : centres) {
            for (int i = 0; i < dims; i++) {
                c[i] = (float) r.nextGaussian();
            }
        }
        float[] rows = new float[n * dims];
        for (int d = 0; d < n; d++) {
            float[] c = centres[r.nextInt(centres.length)];
            double norm = 0;
            for (int i = 0; i < dims; i++) {
                float x = c[i] + 0.7f * (float) r.nextGaussian();
                rows[d * dims + i] = x;
                norm += x * x;
            }
            for (int i = 0; i < dims; i++) {
                rows[d * dims + i] /= (float) Math.sqrt(norm);
            }
        }
        return rows;
    }

    @Test
    void bruteForceFindsTheExactNearest() {
        // 2-d unit vectors at 0°, 90°, 45°, 180°; the query points at 40°
        float h = (float) Math.sqrt(0.5);
        Float32Vectors v = Float32Vectors.of(new float[] {1, 0, 0, 1, h, h, -1, 0}, 2);
        float[] q = {(float) Math.cos(Math.toRadians(40)), (float) Math.sin(Math.toRadians(40))};
        List<ScoredDoc> top = BruteForce.search(v, q, 3);
        assertEquals(List.of(2, 0, 1), top.stream().map(ScoredDoc::docId).toList());
        assertEquals(Math.cos(Math.toRadians(5)), top.get(0).score(), 1e-6);
    }

    @Test
    void nodeHeapOrdersBothWaysAndBreaksTiesByNodeId() {
        NodeHeap max = new NodeHeap(true, 2);
        NodeHeap min = new NodeHeap(false, 2);
        float[] s = {0.5f, 0.9f, 0.1f, 0.9f, 0.3f};
        for (int i = 0; i < s.length; i++) {
            max.push(i, s[i]);
            min.push(i, s[i]);
        }
        int[] fromMax = new int[5];
        int[] fromMin = new int[5];
        for (int i = 0; i < 5; i++) {
            fromMax[i] = max.pop();
            fromMin[i] = min.pop();
        }
        assertArrayEquals(new int[] {1, 3, 0, 4, 2}, fromMax);   // 0.9 (id 1 before id 3), 0.5, 0.3, 0.1
        assertArrayEquals(new int[] {2, 4, 0, 3, 1}, fromMin);   // the exact reverse
    }

    @Test
    void diversityRuleSkipsACandidateThatIsCloserToAKeptNeighbour() {
        // node vectors: 0 = (1,0); 1 = (0.95, 0.31); 2 = (0, 1). The base node points at 30°.
        Float32Vectors v = Float32Vectors.of(new float[] {1, 0, 0.95f, 0.3122f, 0, 1}, 2);
        Hnsw h = Hnsw.build(v, 2, 2, 1, null);
        float[] base = {(float) Math.cos(Math.toRadians(30)), (float) Math.sin(Math.toRadians(30))};
        int[] sorted = {1, 0, 2};
        float[] sims = {v.dot(base, 1), v.dot(base, 0), v.dot(base, 2)};
        // 1 is kept; 0 is more similar to 1 (0.95) than to the base (0.87): skipped; 2 is kept (other direction)
        assertArrayEquals(new int[] {1, 2}, h.selectNeighbours(sorted, sims, 2));
    }

    @Test
    void hnswRecallIsHighAndSaveLoadGivesTheSameAnswers(@TempDir Path tmp) throws IOException {
        int n = 3000;
        int dims = 32;
        Float32Vectors v = Float32Vectors.of(randomUnitVectors(n, dims, 1), dims);
        Hnsw h = Hnsw.build(v, 16, 200, 42, null);
        float[] queries = randomUnitVectors(100, dims, 2);
        float[] q = new float[dims];
        double[] recall = new double[3];
        int[] efs = {10, 64, 1000};
        for (int i = 0; i < 100; i++) {
            System.arraycopy(queries, i * dims, q, 0, dims);
            Set<Integer> exact = new HashSet<>();
            BruteForce.search(v, q, 10).forEach(sd -> exact.add(sd.docId()));
            for (int e = 0; e < efs.length; e++) {
                recall[e] += h.search(q, 10, efs[e]).stream().filter(sd -> exact.contains(sd.docId())).count() / 1000.0;
            }
        }
        // a wider search finds more of the true top 10; ef = 1000 explores a third of the graph
        assertTrue(recall[0] < recall[1] && recall[1] <= recall[2], java.util.Arrays.toString(recall));
        assertTrue(recall[1] >= 0.95, "recall@10 at ef 64 = " + recall[1]);
        assertTrue(recall[2] >= 0.99, "recall@10 at ef 1000 = " + recall[2]);

        Path file = tmp.resolve("hnsw.bin");
        h.save(file);
        Hnsw loaded = Hnsw.load(file, v);
        assertEquals(h.maxLevel(), loaded.maxLevel());
        assertEquals(h.averageDegree(), loaded.averageDegree(), 0);
        for (int i = 0; i < 20; i++) {
            System.arraycopy(queries, i * dims, q, 0, dims);
            assertEquals(h.search(q, 10, 50), loaded.search(q, 10, 50));
        }
        assertTrue(Files.size(file) > 0);
    }

    @Test
    void hnswLayersShrinkByAboutOneOverM() {
        Float32Vectors v = Float32Vectors.of(randomUnitVectors(4000, 8, 3), 8);
        Hnsw h = Hnsw.build(v, 4, 16, 7, null);
        // expected: 4000 at layer 0, ~1000 at layer ≥ 1, ~250 at ≥ 2
        assertEquals(4000, h.nodesAtLevel(0));
        assertTrue(Math.abs(h.nodesAtLevel(1) - 1000) < 120, "layer 1: " + h.nodesAtLevel(1));
        assertTrue(Math.abs(h.nodesAtLevel(2) - 250) < 60, "layer 2: " + h.nodesAtLevel(2));
    }

    @Test
    void tinyGraphs() {
        Float32Vectors one = Float32Vectors.of(new float[] {1, 0}, 2);
        assertEquals(List.of(0), Hnsw.build(one, 2, 4, 1, null).search(new float[] {0, 1}, 5, 10).stream()
                .map(ScoredDoc::docId).toList());
        Float32Vectors two = Float32Vectors.of(new float[] {1, 0, 0, 1}, 2);
        assertEquals(List.of(1, 0), Hnsw.build(two, 2, 4, 1, null).search(new float[] {0, 1}, 5, 10).stream()
                .map(ScoredDoc::docId).toList());
    }

    @Test
    void int8KeepsVectorsCloseAndRoundTripsThroughAFile(@TempDir Path tmp) throws IOException {
        // the worked example from Int8Vectors: (0.5, −0.25, 0.1) → bytes (127, −63, 25); −63.5 rounds up to −63
        byte[] out = new byte[3];
        float scale = Int8Vectors.quantiseRow(new float[] {0.5f, -0.25f, 0.1f}, out, 0);
        assertArrayEquals(new byte[] {127, -63, 25}, out);
        assertEquals(0.5f / 127, scale, 1e-9f);

        int dims = 64;
        Float32Vectors f = Float32Vectors.of(randomUnitVectors(500, dims, 5), dims);
        Int8Vectors i8 = Int8Vectors.quantise(f);
        assertEquals(500L * dims + 4 * 500, i8.bytes());
        float[] q = new float[dims];
        f.copy(7, q);
        for (int d = 0; d < 500; d++) {
            assertEquals(f.dot(q, d), i8.dot(q, d), 0.01);    // similarity changes by less than 0.01
            assertEquals(f.dot(7, d), i8.dot(7, d), 0.01);
        }
        Path file = tmp.resolve("v.bin");
        i8.write(file);
        Int8Vectors back = Int8Vectors.open(file, 500, dims);
        for (int d = 0; d < 500; d++) {
            assertEquals(i8.dot(q, d), back.dot(q, d), 0);
        }
    }
}
