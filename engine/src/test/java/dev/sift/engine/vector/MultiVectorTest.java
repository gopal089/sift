package dev.sift.engine.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sift.engine.search.ScoredDoc;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Two vectors per article: max similarity (exact), stacking, and HNSW with de-duplication. */
class MultiVectorTest {

    private static final float H = (float) Math.sqrt(0.5);

    // 3 documents, 2-d. Set A = "opening words" vectors, set B = "longer text" vectors.
    // doc 0: A at 0°,   B at 180°
    // doc 1: A at 90°,  B at 45°
    // doc 2: A at 180°, B at 270°
    private static final Float32Vectors A = Float32Vectors.of(new float[] {1, 0, 0, 1, -1, 0}, 2);
    private static final Float32Vectors B = Float32Vectors.of(new float[] {-1, 0, H, H, 0, -1}, 2);

    @Test
    void exactIndexScoresEachDocumentByItsBestVector() {
        float[] q = {H, H};                                   // query at 45°
        List<ScoredDoc> top = new ExactIndex("t", A, B).top(q, 3);
        // doc 1: max(cos 45°, cos 0°) = 1; doc 0: max(cos 45°, cos 135°) = 0.707; doc 2: max(cos 135°, cos 135°) = -0.707
        assertEquals(List.of(1, 0, 2), top.stream().map(ScoredDoc::docId).toList());
        assertEquals(1.0, top.get(0).score(), 1e-6);
        assertEquals(H, top.get(1).score(), 1e-6);
        assertEquals(-H, top.get(2).score(), 1e-6);
    }

    @Test
    void exactIndexWithOneSetIsPlainBruteForce() {
        float[] q = {0.6f, 0.8f};
        assertEquals(BruteForce.search(A, q, 3), new ExactIndex("t", A).top(q, 3));
    }

    @Test
    void setsMustHaveTheSameShape() {
        Float32Vectors two = Float32Vectors.of(new float[] {1, 0, 0, 1}, 2);
        assertThrows(IllegalArgumentException.class, () -> new ExactIndex("t", A, two));
        assertThrows(IllegalArgumentException.class, () -> new StackedVectors(A, two));
    }

    @Test
    void stackedVectorsPutSetBAfterSetA() {
        StackedVectors s = new StackedVectors(A, B);
        assertEquals(6, s.size());
        assertEquals(3, s.documents());
        float[] row = new float[2];
        s.copy(4, row);                                       // node 4 = set B, doc 1
        assertEquals(H, row[0], 1e-6);
        assertEquals(H, row[1], 1e-6);
        assertEquals(-1f, s.dot(new float[] {1, 0}, 3), 1e-6); // node 3 = set B, doc 0 = (-1, 0)
        assertEquals(0f, s.dot(0, 1), 1e-6);                  // same set: (1,0)·(0,1)
        assertEquals(H, s.dot(0, 4), 1e-6);                   // across sets: (1,0)·(H,H)
        assertEquals(s.dot(4, 0), s.dot(0, 4), 1e-6);
    }

    @Test
    void hnswOverTwoVectorsReturnsEachDocumentOnceAndMatchesExact() {
        int n = 500;
        int dims = 16;
        Float32Vectors a = Float32Vectors.of(VectorSearchTest.randomUnitVectors(n, dims, 1), dims);
        Float32Vectors b = Float32Vectors.of(VectorSearchTest.randomUnitVectors(n, dims, 2), dims);
        Hnsw graph = Hnsw.build(new StackedVectors(a, b), 16, 200, 42, x -> { });
        HnswIndex hnsw = new HnswIndex("t", graph, 400, n);
        ExactIndex exact = new ExactIndex("t", a, b);

        Random r = new Random(3);
        double recall = 0;
        int queries = 50;
        for (int i = 0; i < queries; i++) {
            float[] q = new float[dims];
            a.copy(r.nextInt(n), q);
            List<ScoredDoc> got = hnsw.top(q, 10);
            assertEquals(10, got.size());
            Set<Integer> docs = new HashSet<>();
            for (ScoredDoc sd : got) {
                assertTrue(sd.docId() < n, "node ids must be mapped back to doc ids");
                assertTrue(docs.add(sd.docId()), "each document at most once");
            }
            Set<Integer> truth = new HashSet<>();
            exact.top(q, 10).forEach(sd -> truth.add(sd.docId()));
            recall += got.stream().filter(sd -> truth.contains(sd.docId())).count() / 10.0;
        }
        assertTrue(recall / queries >= 0.95, "recall " + recall / queries);
    }

    @Test
    void hnswIndexRejectsAGraphThatDoesNotFitTheDocumentCount() {
        Hnsw graph = Hnsw.build(A, 4, 10, 1, x -> { });
        assertThrows(IllegalArgumentException.class, () -> new HnswIndex("t", graph, 10, 2));
    }
}
