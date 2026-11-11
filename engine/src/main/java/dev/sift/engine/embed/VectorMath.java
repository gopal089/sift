package dev.sift.engine.embed;

/** Small vector helpers used by the embedder and the vector search. */
public final class VectorMath {

    private VectorMath() {
    }

    /**
     * Turns the model's per-word-piece vectors into one sentence vector: the average of the vectors whose
     * mask is 1 (real word pieces, not padding), then scaled to length 1.
     *
     * @param tokenVectors [length][dims]
     * @param mask         1 for a real word piece, 0 for padding
     */
    public static float[] meanPoolNormalise(float[][] tokenVectors, long[] mask) {
        int dims = tokenVectors[0].length;
        double[] sum = new double[dims];
        double count = 0;
        for (int t = 0; t < tokenVectors.length; t++) {
            if (mask[t] != 0) {
                for (int i = 0; i < dims; i++) {
                    sum[i] += tokenVectors[t][i];
                }
                count++;
            }
        }
        float[] out = new float[dims];
        double norm = 0;
        for (int i = 0; i < dims; i++) {
            double mean = sum[i] / Math.max(count, 1e-9);
            sum[i] = mean;
            norm += mean * mean;
        }
        norm = Math.max(Math.sqrt(norm), 1e-12);
        for (int i = 0; i < dims; i++) {
            out[i] = (float) (sum[i] / norm);
        }
        return out;
    }

    /** bge-style pooling: the first word piece's vector (the [CLS] position), scaled to length 1. */
    public static float[] clsNormalise(float[][] tokenVectors) {
        float[] first = tokenVectors[0];
        double norm = 0;
        for (float x : first) {
            norm += (double) x * x;
        }
        norm = Math.max(Math.sqrt(norm), 1e-12);
        float[] out = new float[first.length];
        for (int i = 0; i < first.length; i++) {
            out[i] = (float) (first[i] / norm);
        }
        return out;
    }

    /** Σ a[i]·b[i]. For vectors of length 1 this is the cosine of the angle between them. */
    public static float dot(float[] a, float[] b) {
        float s0 = 0, s1 = 0, s2 = 0, s3 = 0;   // four running sums: lets the CPU work on several at once
        int i = 0;
        for (; i + 3 < a.length; i += 4) {
            s0 += a[i] * b[i];
            s1 += a[i + 1] * b[i + 1];
            s2 += a[i + 2] * b[i + 2];
            s3 += a[i + 3] * b[i + 3];
        }
        for (; i < a.length; i++) {
            s0 += a[i] * b[i];
        }
        return (s0 + s1) + (s2 + s3);
    }

    /** Cosine similarity for vectors of any length: dot / (|a| · |b|). */
    public static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return dot / Math.max(Math.sqrt(na) * Math.sqrt(nb), 1e-30);
    }
}
