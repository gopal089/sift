package dev.sift.engine.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TopKHeapTest {

    @Test
    void keepsTheBestKInDescendingOrder() {
        TopKHeap heap = new TopKHeap(3);
        double[] scores = {0.5, 2.0, 1.0, 3.0, 0.1, 2.5};
        for (int doc = 0; doc < scores.length; doc++) {
            heap.offer(doc, scores[doc]);
        }
        assertEquals(List.of(new ScoredDoc(3, 3.0), new ScoredDoc(5, 2.5), new ScoredDoc(1, 2.0)), heap.toSortedList());
    }

    @Test
    void fewerThanKDocuments() {
        TopKHeap heap = new TopKHeap(10);
        heap.offer(7, 1.0);
        heap.offer(2, 4.0);
        assertEquals(2, heap.size());
        assertEquals(List.of(new ScoredDoc(2, 4.0), new ScoredDoc(7, 1.0)), heap.toSortedList());
        assertEquals(List.of(), new TopKHeap(5).toSortedList());
    }

    @Test
    void tiesPreferTheSmallerDocId() {
        TopKHeap heap = new TopKHeap(2);
        heap.offer(9, 1.0);
        heap.offer(4, 1.0);
        heap.offer(6, 1.0);   // equal score: 6 beats 9 but not 4
        assertEquals(List.of(new ScoredDoc(4, 1.0), new ScoredDoc(6, 1.0)), heap.toSortedList());
    }

    @Test
    void rejectsKBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new TopKHeap(0));
    }

    /** Compare against "sort everything and take the first K" on random data. */
    @Test
    void agreesWithFullSortOnRandomData() {
        Random random = new Random(7);
        for (int round = 0; round < 200; round++) {
            int k = 1 + random.nextInt(12);
            int n = random.nextInt(100);
            TopKHeap heap = new TopKHeap(k);
            List<ScoredDoc> all = new ArrayList<>();
            for (int doc = 0; doc < n; doc++) {
                double score = random.nextInt(20) / 4.0;   // small range → many ties
                heap.offer(doc, score);
                all.add(new ScoredDoc(doc, score));
            }
            all.sort(Comparator.comparingDouble(ScoredDoc::score).reversed().thenComparingInt(ScoredDoc::docId));
            assertEquals(all.subList(0, Math.min(k, n)), heap.toSortedList());
        }
    }
}
