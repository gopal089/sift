package dev.sift.engine.index;

import java.util.Arrays;

/**
 * All occurrences of one term: which documents contain it, how often, and where.
 *
 * <p>Stored as three flat int arrays instead of one object per posting (saves a lot of memory):
 * <pre>
 *   docIds    = [ 3,     9,   12      ]   sorted ascending
 *   freqs     = [ 2,     1,   3       ]   tf: how many times the term is in that doc
 *   positions = [ 0, 14, 5,   2, 7, 40 ]  word positions, grouped per doc in the same order
 * </pre>
 * The positions of posting {@code i} start at {@code freqs[0] + ... + freqs[i-1]}.
 */
public final class PostingList {

    private final int[] docIds;
    private final int[] freqs;
    private final int[] positions;

    public PostingList(int[] docIds, int[] freqs, int[] positions) {
        this.docIds = docIds;
        this.freqs = freqs;
        this.positions = positions;
    }

    /** Number of documents that contain the term (the "document frequency", n). */
    public int size() {
        return docIds.length;
    }

    public int docId(int i) {
        return docIds[i];
    }

    public int freq(int i) {
        return freqs[i];
    }

    /** The sorted doc ids. Callers must not modify the array. */
    public int[] docIds() {
        return docIds;
    }

    /** Word positions of the term in posting {@code i}. O(i): adds up the freqs before it. */
    public int[] positions(int i) {
        int start = 0;
        for (int p = 0; p < i; p++) {
            start += freqs[p];
        }
        return Arrays.copyOfRange(positions, start, start + freqs[i]);
    }

    public int positionCount() {
        return positions.length;
    }

    /** Bytes used by the three arrays' contents (4 bytes per int), not counting object headers. */
    public long dataBytes() {
        return 4L * (docIds.length + freqs.length + positions.length);
    }

    /** Collects postings for one term while documents are added in increasing doc id order. */
    static final class Builder {
        private final IntArrayList docIds = new IntArrayList(2);
        private final IntArrayList freqs = new IntArrayList(2);
        private final IntArrayList positions = new IntArrayList(4);

        void add(int docId, IntArrayList termPositions) {
            if (docIds.size() > 0 && docIds.get(docIds.size() - 1) >= docId) {
                throw new IllegalArgumentException("doc ids must be added in increasing order");
            }
            docIds.add(docId);
            freqs.add(termPositions.size());
            for (int i = 0; i < termPositions.size(); i++) {
                positions.add(termPositions.get(i));
            }
        }

        PostingList build() {
            return new PostingList(docIds.toArray(), freqs.toArray(), positions.toArray());
        }
    }
}
