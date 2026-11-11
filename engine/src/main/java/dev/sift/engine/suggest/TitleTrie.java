package dev.sift.engine.suggest;

import dev.sift.engine.index.DocTable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Autocomplete: a trie (prefix tree) of all article titles. Every node stores its top 5 completions,
 * ranked by PageRank, so answering a prefix is just "walk down the letters, read the node's list".
 *
 * <pre>
 *   root ─ p ─ y ─ t ─ h ─ o ─ n      node "pyt" stores [Python, Pythagoras, ...] (the 5 best titles below it)
 *                      └─ a ─ g ...
 * </pre>
 * Building trick: titles are inserted from the highest PageRank to the lowest. Each node on a title's path
 * keeps the title only if it has fewer than 5 so far. So every node ends up with its 5 most important
 * titles, already in order, with no sorting per node.
 *
 * <p>To keep memory small the tree is stored in flat arrays (not one object per node), and only the first
 * {@link #MAX_DEPTH} characters of a title are in the tree; longer prefixes are checked against the stored titles.
 */
public final class TitleTrie {

    public static final int TOP = 5;
    static final int MAX_DEPTH = 24;

    private final DocTable docs;
    private int nodes = 1;                    // node 0 is the root
    private char[] letter = new char[1024];   // the character on the edge into this node
    private int[] firstChild = new int[1024]; // 0 = no child (the root can never be a child)
    private int[] nextSibling = new int[1024];
    private int[] top = filled(1024 * TOP);   // TOP doc ids per node, -1 = empty slot

    public TitleTrie(DocTable docs) {
        this.docs = docs;
        Integer[] order = new Integer[docs.size()];
        for (int d = 0; d < order.length; d++) {
            order[d] = d;
        }
        // most important first; ties (e.g. no PageRank at all) by title so the result is always the same
        Arrays.sort(order, (a, b) -> {
            int byRank = Double.compare(docs.pageRank(b), docs.pageRank(a));
            return byRank != 0 ? byRank : docs.title(a).compareTo(docs.title(b));
        });
        for (int doc : order) {
            insert(normalize(docs.title(doc)), doc);
        }
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    private static int[] filled(int length) {
        int[] a = new int[length];
        Arrays.fill(a, -1);
        return a;
    }

    private void insert(String key, int doc) {
        int node = 0;
        for (int i = 0; i < key.length() && i < MAX_DEPTH; i++) {
            node = childOrCreate(node, key.charAt(i));
            remember(node, doc);
        }
    }

    /** Adds the doc to the node's top list if there is still room (earlier docs have a higher PageRank). */
    private void remember(int node, int doc) {
        for (int slot = node * TOP; slot < node * TOP + TOP; slot++) {
            if (top[slot] < 0) {
                top[slot] = doc;
                return;
            }
        }
    }

    /** Children of a node form a linked list: firstChild, then nextSibling, nextSibling, ... */
    private int child(int node, char c) {
        for (int child = firstChild[node]; child != 0; child = nextSibling[child]) {
            if (letter[child] == c) {
                return child;
            }
        }
        return 0;
    }

    private int childOrCreate(int node, char c) {
        int existing = child(node, c);
        if (existing != 0) {
            return existing;
        }
        if (nodes == letter.length) {   // arrays full: double them
            letter = Arrays.copyOf(letter, nodes * 2);
            firstChild = Arrays.copyOf(firstChild, nodes * 2);
            nextSibling = Arrays.copyOf(nextSibling, nodes * 2);
            int[] biggerTop = filled(nodes * 2 * TOP);
            System.arraycopy(top, 0, biggerTop, 0, nodes * TOP);
            top = biggerTop;
        }
        int created = nodes++;
        letter[created] = c;
        nextSibling[created] = firstChild[node];   // put the new child at the front of the list
        firstChild[node] = created;
        return created;
    }

    /** Up to {@code limit} (at most 5) titles starting with {@code prefix}, most important first. */
    public List<String> suggest(String prefix, int limit) {
        String p = normalize(prefix.strip());
        List<String> out = new ArrayList<>();
        if (p.isEmpty()) {
            return out;
        }
        int node = 0;
        for (int i = 0; i < p.length() && i < MAX_DEPTH; i++) {
            node = child(node, p.charAt(i));
            if (node == 0) {
                return out;   // no title starts with this prefix
            }
        }
        for (int slot = node * TOP; slot < node * TOP + TOP && out.size() < limit; slot++) {
            if (top[slot] < 0) {
                break;
            }
            String title = docs.title(top[slot]);
            // only needed for prefixes longer than MAX_DEPTH: the tree stops there, so check the rest
            if (p.length() <= MAX_DEPTH || normalize(title).startsWith(p)) {
                out.add(title);
            }
        }
        return out;
    }

    public int nodeCount() {
        return nodes;
    }
}
