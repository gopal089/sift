package dev.sift.engine.spell;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * BK-tree: finds all words within a small edit distance of a query without comparing against every word.
 *
 * <p>Each node holds one word. A child hangs under its parent on a branch labelled with its distance to
 * the parent:
 * <pre>
 *              book
 *          1 /   \ 4
 *        books    cake           "books" is 1 edit from "book"; "cake" is 4 edits from "book"
 *                 1 |
 *                 cape           "cape" is also 4 from "book", so it goes down the 4-branch: 1 edit from "cake"
 * </pre>
 * Search for words within distance k of a query q: at a node with word w, let d = distance(q, w).
 * Any word x in the branch labelled c has distance(w, x) = c. By the <b>triangle inequality</b>
 * (a detour is never shorter than the direct way), distance(q, x) is at least |d − c|. So branches with
 * |d − c| &gt; k cannot contain a match and are skipped. This only works for a real metric, which is why
 * the tree uses plain Levenshtein distance.
 */
public final class BkTree {

    /** One match: a word (by its index in insertion order) and its distance to the query. */
    public record Match(int wordIndex, int distance) {
    }

    private final List<String> words = new ArrayList<>();
    private final List<int[]> children = new ArrayList<>();   // children.get(node)[distance] = child node, 0 = none
    private int distanceCalls;                                // work done by the last search, for measuring

    /** Adds a word and returns its index (0, 1, 2, ...). Adding the same word twice keeps one copy. */
    public int add(String word) {
        if (words.isEmpty()) {
            return newNode(word);
        }
        int node = 0;
        while (true) {
            int d = EditDistance.levenshtein(word, words.get(node));
            if (d == 0) {
                return node;   // already in the tree
            }
            int[] branches = children.get(node);
            if (d >= branches.length) {
                branches = Arrays.copyOf(branches, d + 1);
                children.set(node, branches);
            }
            if (branches[d] == 0) {
                branches[d] = newNode(word);
                return branches[d];
            }
            node = branches[d];   // the branch is taken: go down and try again there
        }
    }

    private int newNode(String word) {
        words.add(word);
        children.add(new int[0]);
        return words.size() - 1;
    }

    /** All words within {@code maxDistance} edits of {@code query}. */
    public List<Match> search(String query, int maxDistance) {
        List<Match> matches = new ArrayList<>();
        distanceCalls = 0;
        if (words.isEmpty()) {
            return matches;
        }
        Deque<Integer> toVisit = new ArrayDeque<>();
        toVisit.push(0);
        while (!toVisit.isEmpty()) {
            int node = toVisit.pop();
            int d = EditDistance.levenshtein(query, words.get(node));
            distanceCalls++;
            if (d <= maxDistance) {
                matches.add(new Match(node, d));
            }
            int[] branches = children.get(node);
            // only branches labelled d − k … d + k can hold a match
            for (int c = Math.max(1, d - maxDistance); c <= d + maxDistance && c < branches.length; c++) {
                if (branches[c] != 0) {
                    toVisit.push(branches[c]);
                }
            }
        }
        return matches;
    }

    public String word(int index) {
        return words.get(index);
    }

    public int size() {
        return words.size();
    }

    /** How many edit distances the last {@link #search} computed (the rest of the tree was skipped). */
    public int lastSearchDistanceCalls() {
        return distanceCalls;
    }
}
