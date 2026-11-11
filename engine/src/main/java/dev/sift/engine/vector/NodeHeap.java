package dev.sift.engine.vector;

import java.util.Arrays;

/**
 * A binary heap of (node, similarity) pairs, without boxing. {@code maxFirst = true}: the most similar
 * node is on top (HNSW's "candidates to explore"); {@code false}: the least similar is on top (HNSW's
 * "best results so far", where we must quickly find and drop the worst). Equal similarity: smaller node
 * id counts as more similar, so results never depend on luck.
 */
final class NodeHeap {

    private final boolean maxFirst;
    private int[] nodes;
    private float[] scores;
    private int size;

    NodeHeap(boolean maxFirst, int initialCapacity) {
        this.maxFirst = maxFirst;
        this.nodes = new int[Math.max(initialCapacity, 4)];
        this.scores = new float[nodes.length];
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    void clear() {
        size = 0;
    }

    int topNode() {
        return nodes[0];
    }

    float topScore() {
        return scores[0];
    }

    void push(int node, float score) {
        if (size == nodes.length) {
            nodes = Arrays.copyOf(nodes, size * 2);
            scores = Arrays.copyOf(scores, size * 2);
        }
        int i = size++;
        while (i > 0) {                       // move up while the parent should be below us
            int parent = (i - 1) >>> 1;
            if (!above(node, score, nodes[parent], scores[parent])) {
                break;
            }
            nodes[i] = nodes[parent];
            scores[i] = scores[parent];
            i = parent;
        }
        nodes[i] = node;
        scores[i] = score;
    }

    /** Removes the top pair and returns its node. */
    int pop() {
        int top = nodes[0];
        size--;
        if (size > 0) {
            int node = nodes[size];
            float score = scores[size];
            int i = 0;
            while (true) {                    // move the last pair down from the root
                int child = 2 * i + 1;
                if (child >= size) {
                    break;
                }
                if (child + 1 < size && above(nodes[child + 1], scores[child + 1], nodes[child], scores[child])) {
                    child++;
                }
                if (!above(nodes[child], scores[child], node, score)) {
                    break;
                }
                nodes[i] = nodes[child];
                scores[i] = scores[child];
                i = child;
            }
            nodes[i] = node;
            scores[i] = score;
        }
        return top;
    }

    /** Should (a) be nearer the top than (b)? */
    private boolean above(int nodeA, float scoreA, int nodeB, float scoreB) {
        if (scoreA != scoreB) {
            return maxFirst ? scoreA > scoreB : scoreA < scoreB;
        }
        return maxFirst ? nodeA < nodeB : nodeA > nodeB;   // smaller id = "more similar"
    }
}
