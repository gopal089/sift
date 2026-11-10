package dev.sift.engine.search;

/**
 * BM25F: BM25 over a document with two fields, title and body (Robertson, Zaragoza and Taylor, 2004).
 *
 * <pre>
 *   B_f      = 1 − b_f + b_f × len_f(d) / avgLen_f              length normalisation, per field
 *   tf~(t,d) = w_title × tf_title / B_title + tf_body / B_body     combine the fields' counts FIRST
 *   score    = idf(t) × tf~ × (k1 + 1) / (k1 + tf~)               then saturate ONCE
 * </pre>
 * Why combine first: if we added two separate BM25 scores, a word in the title AND the body would get the
 * big "first mention" bonus twice. Here it is one pile of evidence that saturates once.
 * With w_title = 0 (or no title match), this is exactly ordinary BM25 over the body.
 */
public final class Bm25F {

    private final double k1;
    private final double wTitle;
    private final double bTitle;
    private final double bBody;
    private final double avgTitleLength;
    private final double avgBodyLength;

    public Bm25F(double k1, double wTitle, double bTitle, double bBody, double avgTitleLength, double avgBodyLength) {
        this.k1 = k1;
        this.wTitle = wTitle;
        this.bTitle = bTitle;
        this.bBody = bBody;
        this.avgTitleLength = avgTitleLength;
        this.avgBodyLength = avgBodyLength;
    }

    /** BM25F contribution of one term to one document. */
    public double termScore(double idf, int tfTitle, int titleLength, int tfBody, int bodyLength) {
        double tf = 0;
        if (tfTitle > 0) {
            tf += wTitle * tfTitle / norm(bTitle, titleLength, avgTitleLength);
        }
        if (tfBody > 0) {
            tf += tfBody / norm(bBody, bodyLength, avgBodyLength);
        }
        return idf * tf * (k1 + 1.0) / (k1 + tf);
    }

    private static double norm(double b, int length, double avgLength) {
        return 1.0 - b + b * length / avgLength;
    }
}
