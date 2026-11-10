package dev.sift.engine.search;

/**
 * BM25 ("Best Match 25") relevance scoring.
 *
 * <pre>
 *   idf(t)        = ln( (N − n + 0.5) / (n + 0.5) + 1 )
 *   score(t, d)   = idf(t) × tf × (k1 + 1) / ( tf + k1 × (1 − b + b × len(d) / avgLen) )
 *   score(q, d)   = sum of score(t, d) over the query terms t
 * </pre>
 * <ul>
 *   <li><b>N</b>: number of documents; <b>n</b>: documents containing t; <b>tf</b>: times t appears in d.</li>
 *   <li><b>idf</b>: rare terms count more. The "+1" keeps it positive even for very common words.</li>
 *   <li><b>k1</b> (1.2): how fast extra mentions stop helping (saturation).</li>
 *   <li><b>b</b> (0.75): how much long documents are penalised (0 = not at all, 1 = fully).</li>
 * </ul>
 */
public final class Bm25 {

    public static final double DEFAULT_K1 = 1.2;
    public static final double DEFAULT_B = 0.75;

    private final double k1;
    private final double b;
    private final int documentCount;
    private final double averageLength;

    public Bm25(int documentCount, double averageLength) {
        this(documentCount, averageLength, DEFAULT_K1, DEFAULT_B);
    }

    public Bm25(int documentCount, double averageLength, double k1, double b) {
        if (documentCount <= 0 || averageLength <= 0) {
            throw new IllegalArgumentException("need at least one non-empty document");
        }
        this.documentCount = documentCount;
        this.averageLength = averageLength;
        this.k1 = k1;
        this.b = b;
    }

    /** Inverse document frequency of a term found in {@code n} documents. */
    public double idf(int n) {
        return Math.log((documentCount - n + 0.5) / (n + 0.5) + 1.0);
    }

    @Override
    public String toString() {
        return "BM25 k1=" + k1 + " b=" + b;
    }

    /** BM25 contribution of one term to one document. */
    public double termScore(double idf, int tf, int documentLength) {
        double lengthNorm = k1 * (1.0 - b + b * documentLength / averageLength);
        return idf * tf * (k1 + 1.0) / (tf + lengthNorm);
    }
}
