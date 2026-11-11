package dev.sift.engine.vector;

import dev.sift.engine.search.ScoredDoc;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.IntConsumer;

/**
 * HNSW ("Hierarchical Navigable Small World"), written by hand: an approximate nearest-neighbour index.
 *
 * <p><b>Idea.</b> Every document is a node in a graph, linked to a few documents with similar vectors.
 * To search, start somewhere and keep walking to a neighbour that is more similar to the query; stop when
 * no neighbour is better. One flat graph needs many steps to cross 280k nodes, so HNSW stacks layers like
 * an express train: layer 0 has every node; each higher layer keeps a random ~1/M of the layer below.
 * A search walks the sparse top layer first (big jumps), then drops a layer at a time (smaller steps).
 *
 * <p><b>Settings.</b> {@code M} = links per node on upper layers (2M on layer 0);
 * {@code efConstruction} = how widely to search for neighbours while building;
 * {@code efSearch} = how many candidates a query keeps (bigger: better recall, slower).
 *
 * <p>Similarity is the dot product (vectors have length 1, so it is the cosine): bigger = closer.
 */
public final class Hnsw {

    static final int MAGIC = 0x48534E57;   // "HNSW"
    static final int VERSION = 1;

    private final VectorStore vectors;
    private final int m;
    private final int m0;
    private final int efConstruction;
    private final long seed;
    private final byte[] levels;            // top layer of each node
    private final int[] layer0;             // node × m0 neighbour slots
    private final int[] layer0Count;
    private final int[][] upper;            // upper[node]: per layer 1..level, [count, m slots]; null if level 0
    private int entryPoint = -1;
    private int maxLevel = -1;

    private final ThreadLocal<Visited> visited;

    private Hnsw(VectorStore vectors, int m, int efConstruction, long seed) {
        this.vectors = vectors;
        this.m = m;
        this.m0 = 2 * m;
        this.efConstruction = efConstruction;
        this.seed = seed;
        int n = vectors.size();
        this.levels = new byte[n];
        this.layer0 = new int[n * m0];
        this.layer0Count = new int[n];
        this.upper = new int[n][];
        this.visited = ThreadLocal.withInitial(() -> new Visited(n));
    }

    // ================================================================== build

    /**
     * Builds the graph by inserting documents 0, 1, 2, … one at a time.
     *
     * @param progress called with the number of inserted nodes every 10,000 nodes (may be null)
     */
    public static Hnsw build(VectorStore vectors, int m, int efConstruction, long seed, IntConsumer progress) {
        if (m < 2 || efConstruction < m) {
            throw new IllegalArgumentException("need M >= 2 and efConstruction >= M");
        }
        Hnsw h = new Hnsw(vectors, m, efConstruction, seed);
        // Random top layer: P(level >= L) = (1/M)^L, so each layer up has about 1/M of the nodes below.
        SplittableRandom random = new SplittableRandom(seed);
        double mL = 1 / Math.log(m);
        for (int d = 0; d < h.levels.length; d++) {
            double u = 1.0 - random.nextDouble();                  // in (0, 1], so log(u) is finite
            h.levels[d] = (byte) Math.min(Byte.MAX_VALUE, (int) Math.floor(-Math.log(u) * mL));
        }
        float[] q = new float[vectors.dims()];
        for (int d = 0; d < h.levels.length; d++) {
            vectors.copy(d, q);
            h.insert(d, q);
            if (progress != null && (d + 1) % 10_000 == 0) {
                progress.accept(d + 1);
            }
        }
        return h;
    }

    private void insert(int node, float[] q) {
        int level = levels[node];
        if (level > 0) {
            upper[node] = new int[level * (m + 1)];
        }
        if (entryPoint < 0) {
            entryPoint = node;
            maxLevel = level;
            return;
        }
        int ep = entryPoint;
        for (int lc = maxLevel; lc > level; lc--) {
            ep = greedyClosest(q, ep, lc);                  // upper layers: just walk downhill
        }
        for (int lc = Math.min(level, maxLevel); lc >= 0; lc--) {
            NodeHeap found = searchLayer(q, ep, efConstruction, lc);
            int[] sorted = drainBestFirst(found);
            int[] chosen = selectNeighbours(sorted, scoresTo(q, sorted), m);
            for (int c : chosen) {
                addLink(node, c, lc);
                addLinkPruning(c, node, lc);                // links go both ways
            }
            ep = sorted[0];                                 // best match so far: start of the next layer down
        }
        if (level > maxLevel) {
            entryPoint = node;
            maxLevel = level;
        }
    }

    /**
     * The "diversity" rule: go through candidates from most to least similar and keep one only if it is
     * more similar to the new node than to every neighbour already kept. This spreads links in different
     * directions instead of keeping five near-copies of one direction, which keeps the graph navigable.
     *
     * @param sorted candidates, most similar first
     * @param sims   their similarity to the node being linked
     */
    int[] selectNeighbours(int[] sorted, float[] sims, int max) {
        int[] kept = new int[Math.min(max, sorted.length)];
        int n = 0;
        for (int i = 0; i < sorted.length && n < max; i++) {
            int c = sorted[i];
            boolean diverse = true;
            for (int j = 0; j < n; j++) {
                if (vectors.dot(c, kept[j]) > sims[i]) {    // c is closer to a kept neighbour than to us
                    diverse = false;
                    break;
                }
            }
            if (diverse) {
                kept[n++] = c;
            }
        }
        return Arrays.copyOf(kept, n);
    }

    private void addLink(int from, int to, int lc) {
        if (lc == 0) {
            layer0[from * m0 + layer0Count[from]++] = to;
        } else {
            int base = (lc - 1) * (m + 1);
            int[] u = upper[from];
            u[base + 1 + u[base]++] = to;
        }
    }

    /** Adds a link; if the node already has the maximum, re-chooses its neighbours with the diversity rule. */
    private void addLinkPruning(int from, int to, int lc) {
        int cap = lc == 0 ? m0 : m;
        int count = neighbourCount(from, lc);
        if (count < cap) {
            addLink(from, to, lc);
            return;
        }
        int[] all = new int[count + 1];
        for (int i = 0; i < count; i++) {
            all[i] = neighbour(from, lc, i);
        }
        all[count] = to;
        float[] sims = new float[all.length];
        for (int i = 0; i < all.length; i++) {
            sims[i] = vectors.dot(from, all[i]);
        }
        sortBestFirst(all, sims);
        int[] kept = selectNeighbours(all, sims, cap);
        setNeighbours(from, lc, kept);
    }

    private void setNeighbours(int node, int lc, int[] list) {
        if (lc == 0) {
            System.arraycopy(list, 0, layer0, node * m0, list.length);
            layer0Count[node] = list.length;
        } else {
            int base = (lc - 1) * (m + 1);
            System.arraycopy(list, 0, upper[node], base + 1, list.length);
            upper[node][base] = list.length;
        }
    }

    // ================================================================== search

    /** The K most similar documents (approximately), best first. */
    public List<ScoredDoc> search(float[] q, int k, int efSearch) {
        if (entryPoint < 0) {
            return List.of();
        }
        int ep = entryPoint;
        for (int lc = maxLevel; lc > 0; lc--) {
            ep = greedyClosest(q, ep, lc);
        }
        NodeHeap found = searchLayer(q, ep, Math.max(efSearch, k), 0);
        int[] best = drainBestFirst(found);
        List<ScoredDoc> out = new ArrayList<>(Math.min(k, best.length));
        for (int i = 0; i < best.length && i < k; i++) {
            out.add(new ScoredDoc(best[i], vectors.dot(q, best[i])));
        }
        return out;
    }

    /** Walk to a more similar neighbour until there is none (beam width 1). */
    private int greedyClosest(float[] q, int start, int lc) {
        int current = start;
        float currentSim = vectors.dot(q, current);
        boolean moved = true;
        while (moved) {
            moved = false;
            int from = current;                             // read this node's whole list, even if we move
            int count = neighbourCount(from, lc);
            for (int i = 0; i < count; i++) {
                int e = neighbour(from, lc, i);
                float s = vectors.dot(q, e);
                if (s > currentSim || (s == currentSim && e < current)) {
                    current = e;
                    currentSim = s;
                    moved = true;
                }
            }
        }
        return current;
    }

    /**
     * Beam search on one layer. {@code candidates} (most similar on top) are nodes still to expand;
     * {@code results} (least similar on top) are the best {@code ef} seen. Stop when the best candidate
     * left is worse than the worst result: nothing reachable from it can improve the list (that is the bet).
     */
    private NodeHeap searchLayer(float[] q, int ep, int ef, int lc) {
        Visited seen = visited.get();
        seen.reset();
        NodeHeap candidates = new NodeHeap(true, ef * 2);
        NodeHeap results = new NodeHeap(false, ef + 1);
        float s = vectors.dot(q, ep);
        seen.add(ep);
        candidates.push(ep, s);
        results.push(ep, s);
        while (!candidates.isEmpty()) {
            float cs = candidates.topScore();
            if (results.size() >= ef && cs < results.topScore()) {
                break;
            }
            int c = candidates.pop();
            int count = neighbourCount(c, lc);
            for (int i = 0; i < count; i++) {
                int e = neighbour(c, lc, i);
                if (!seen.add(e)) {
                    continue;
                }
                float se = vectors.dot(q, e);
                if (results.size() < ef || se > results.topScore()) {
                    candidates.push(e, se);
                    results.push(e, se);
                    if (results.size() > ef) {
                        results.pop();                      // drop the least similar
                    }
                }
            }
        }
        return results;
    }

    private int neighbourCount(int node, int lc) {
        return lc == 0 ? layer0Count[node] : upper[node][(lc - 1) * (m + 1)];
    }

    private int neighbour(int node, int lc, int i) {
        return lc == 0 ? layer0[node * m0 + i] : upper[node][(lc - 1) * (m + 1) + 1 + i];
    }

    /** Empties a least-similar-on-top heap into an array, most similar first. */
    private static int[] drainBestFirst(NodeHeap results) {
        int[] out = new int[results.size()];
        for (int i = out.length - 1; i >= 0; i--) {
            out[i] = results.pop();
        }
        return out;
    }

    private float[] scoresTo(float[] q, int[] nodes) {
        float[] s = new float[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            s[i] = vectors.dot(q, nodes[i]);
        }
        return s;
    }

    /** Sorts both arrays together: highest similarity first, then smaller node id (insertion sort; ≤ 33 items). */
    private static void sortBestFirst(int[] nodes, float[] sims) {
        for (int i = 1; i < nodes.length; i++) {
            int n = nodes[i];
            float s = sims[i];
            int j = i - 1;
            while (j >= 0 && (sims[j] < s || (sims[j] == s && nodes[j] > n))) {
                nodes[j + 1] = nodes[j];
                sims[j + 1] = sims[j];
                j--;
            }
            nodes[j + 1] = n;
            sims[j + 1] = s;
        }
    }

    /** "Have I seen this node in this search?" without clearing an array each time: a stamp per search. */
    private static final class Visited {
        private final int[] stamps;
        private int stamp;

        Visited(int n) {
            stamps = new int[n];
        }

        void reset() {
            if (++stamp == Integer.MAX_VALUE) {
                Arrays.fill(stamps, 0);
                stamp = 1;
            }
        }

        /** True if the node was not seen yet (and marks it seen). */
        boolean add(int node) {
            if (stamps[node] == stamp) {
                return false;
            }
            stamps[node] = stamp;
            return true;
        }
    }

    // ================================================================== info, save, load

    public int m() {
        return m;
    }

    public int efConstruction() {
        return efConstruction;
    }

    public int maxLevel() {
        return maxLevel;
    }

    public int size() {
        return levels.length;
    }

    /** Number of nodes whose top layer is at least {@code level}. */
    public int nodesAtLevel(int level) {
        int c = 0;
        for (byte l : levels) {
            if (l >= level) {
                c++;
            }
        }
        return c;
    }

    /** Average number of layer-0 links per node. */
    public double averageDegree() {
        long sum = 0;
        for (int c : layer0Count) {
            sum += c;
        }
        return (double) sum / levels.length;
    }

    /** Bytes of the vectors this graph searches (memory-mapped, outside the heap). */
    public long vectorBytes() {
        return vectors.bytes();
    }

    /** Heap bytes of the graph arrays (not the vectors). */
    public long graphBytes() {
        long b = levels.length + 4L * layer0.length + 4L * layer0Count.length + 8L * upper.length;
        for (int[] u : upper) {
            if (u != null) {
                b += 16 + 4L * u.length;
            }
        }
        return b;
    }

    /** Saves the graph (not the vectors): header, levels, layer-0 links, upper-layer links. */
    public void save(Path file) throws IOException {
        int n = levels.length;
        long upperInts = 0;
        for (int[] u : upper) {
            if (u != null) {
                upperInts += u.length;
            }
        }
        long bytes = 4L * 9 + n + 4L * n + 4L * n * m0 + 4L * upperInts;
        ByteBuffer buf = ByteBuffer.allocate(Math.toIntExact(bytes)).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(MAGIC).putInt(VERSION).putInt(n).putInt(vectors.dims()).putInt(m).putInt(efConstruction)
                .putInt(entryPoint).putInt(maxLevel).putInt((int) seed);
        buf.put(levels);
        IntBuffer ints = buf.slice(buf.position(), buf.remaining()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        ints.put(layer0Count).put(layer0);
        for (int[] u : upper) {
            if (u != null) {
                ints.put(u);
            }
        }
        buf.rewind();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
        }
    }

    /** Loads a saved graph and attaches it to {@code vectors} (float32 or int8: the links are the same). */
    public static Hnsw load(Path file, VectorStore vectors) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            buf.order(ByteOrder.LITTLE_ENDIAN);
            if (buf.getInt() != MAGIC || buf.getInt() != VERSION) {
                throw new IOException(file + " is not an HNSW graph of version " + VERSION);
            }
            int n = buf.getInt();
            int dims = buf.getInt();
            if (n != vectors.size() || dims != vectors.dims()) {
                throw new IOException(file + " was built for " + n + " × " + dims + " vectors, but the vectors are "
                        + vectors.size() + " × " + vectors.dims());
            }
            int m = buf.getInt();
            int efc = buf.getInt();
            int entry = buf.getInt();
            int maxLevel = buf.getInt();
            long seed = buf.getInt();
            Hnsw h = new Hnsw(vectors, m, efc, seed);
            buf.get(h.levels);
            IntBuffer ints = buf.slice(buf.position(), buf.remaining()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            ints.get(h.layer0Count).get(h.layer0);
            for (int d = 0; d < n; d++) {
                if (h.levels[d] > 0) {
                    h.upper[d] = new int[h.levels[d] * (m + 1)];
                    ints.get(h.upper[d]);
                }
            }
            h.entryPoint = entry;
            h.maxLevel = maxLevel;
            return h;
        }
    }
}
