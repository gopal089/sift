package dev.sift.engine.vector;

/**
 * Several vector sets seen as one: rows of set 0, then rows of set 1, … Node {@code i} belongs to
 * document {@code i % documents}. Lets one HNSW graph hold two vectors per article.
 */
public final class StackedVectors implements VectorStore {

    private final VectorStore[] parts;
    private final int documents;

    public StackedVectors(VectorStore... parts) {
        for (VectorStore p : parts) {
            if (p.size() != parts[0].size() || p.dims() != parts[0].dims()) {
                throw new IllegalArgumentException("all vector sets must have the same size and dims");
            }
        }
        this.parts = parts.clone();
        this.documents = parts[0].size();
    }

    public int documents() {
        return documents;
    }

    @Override
    public int size() {
        return documents * parts.length;
    }

    @Override
    public int dims() {
        return parts[0].dims();
    }

    @Override
    public float dot(float[] q, int node) {
        return parts[node / documents].dot(q, node % documents);
    }

    @Override
    public float dot(int a, int b) {
        int pa = a / documents;
        if (pa == b / documents) {
            return parts[pa].dot(a % documents, b % documents);
        }
        float[] row = buffer.get();     // different sets: decode one row, then a query-style dot product
        copy(a, row);
        return dot(row, b);
    }

    private final ThreadLocal<float[]> buffer = ThreadLocal.withInitial(() -> new float[dims()]);

    @Override
    public void copy(int node, float[] out) {
        parts[node / documents].copy(node % documents, out);
    }

    @Override
    public long bytes() {
        long b = 0;
        for (VectorStore p : parts) {
            b += p.bytes();
        }
        return b;
    }

    @Override
    public String kind() {
        return parts[0].kind();
    }
}
