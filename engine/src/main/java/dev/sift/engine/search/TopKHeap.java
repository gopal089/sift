package dev.sift.engine.search;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the K best-scoring documents seen so far, using a min-heap of size K.
 *
 * <p>The heap is stored in arrays: the children of slot i are 2i+1 and 2i+2, its parent is (i−1)/2.
 * The <b>worst</b> of the current top K is always at slot 0 (the root), so deciding whether a new
 * document gets in is one comparison, and inserting it costs O(log K).
 *
 * <p>Total cost for n candidates: O(n log K) time and O(K) memory, instead of O(n log n) to sort all.
 * Ties: a higher score wins; for equal scores the smaller doc id wins (so results are deterministic).
 */
public final class TopKHeap {

    private final int capacity;
    private final int[] docIds;
    private final double[] scores;
    private int size;

    public TopKHeap(int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be at least 1");
        }
        this.capacity = k;
        this.docIds = new int[k];
        this.scores = new double[k];
    }

    /** Offers a document; it is kept only if it belongs in the top K. */
    public void offer(int docId, double score) {
        if (size < capacity) {
            docIds[size] = docId;
            scores[size] = score;
            siftUp(size);
            size++;
        } else if (isWorse(docIds[0], scores[0], docId, score)) {
            // the new document beats the current worst: replace the root and restore the heap
            docIds[0] = docId;
            scores[0] = score;
            siftDown(0);
        }
    }

    public int size() {
        return size;
    }

    /** The kept documents, best first. Does not change the heap. */
    public List<ScoredDoc> toSortedList() {
        List<ScoredDoc> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(new ScoredDoc(docIds[i], scores[i]));
        }
        out.sort((a, b) -> isWorse(a.docId(), a.score(), b.docId(), b.score()) ? 1
                : isWorse(b.docId(), b.score(), a.docId(), a.score()) ? -1 : 0);
        return out;
    }

    /** True if document a ranks below document b. */
    private static boolean isWorse(int docA, double scoreA, int docB, double scoreB) {
        if (scoreA != scoreB) {
            return scoreA < scoreB;
        }
        return docA > docB;
    }

    /** Move the element at i up while it is worse than its parent. */
    private void siftUp(int i) {
        while (i > 0) {
            int parent = (i - 1) / 2;
            if (!isWorse(docIds[i], scores[i], docIds[parent], scores[parent])) {
                break;
            }
            swap(i, parent);
            i = parent;
        }
    }

    /** Move the element at i down while one of its children is worse than it. */
    private void siftDown(int i) {
        while (true) {
            int left = 2 * i + 1;
            int right = left + 1;
            int worst = i;
            if (left < size && isWorse(docIds[left], scores[left], docIds[worst], scores[worst])) {
                worst = left;
            }
            if (right < size && isWorse(docIds[right], scores[right], docIds[worst], scores[worst])) {
                worst = right;
            }
            if (worst == i) {
                return;
            }
            swap(i, worst);
            i = worst;
        }
    }

    private void swap(int a, int b) {
        int d = docIds[a];
        docIds[a] = docIds[b];
        docIds[b] = d;
        double s = scores[a];
        scores[a] = scores[b];
        scores[b] = s;
    }
}
