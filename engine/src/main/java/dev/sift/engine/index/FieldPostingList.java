package dev.sift.engine.index;

/**
 * One term's postings inside a single field (here: the title field), without positions.
 *
 * <pre>
 *   docIds = [ 4, 17, 230 ]   sorted ascending; always a subset of the term's main posting list
 *   freqs  = [ 1,  2,   1 ]   how many times the term is in that document's title field
 * </pre>
 * Titles are short, so this list is tiny compared with the main one (about 1 entry per title word).
 */
public final class FieldPostingList {

    private final int[] docIds;
    private final int[] freqs;

    FieldPostingList(int[] docIds, int[] freqs) {
        this.docIds = docIds;
        this.freqs = freqs;
    }

    public int size() {
        return docIds.length;
    }

    public int docId(int i) {
        return docIds[i];
    }

    public int freq(int i) {
        return freqs[i];
    }

    public long dataBytes() {
        return 4L * (docIds.length + freqs.length);
    }

    static final class Builder {
        private final IntArrayList docIds = new IntArrayList(1);
        private final IntArrayList freqs = new IntArrayList(1);

        void add(int docId, int freq) {
            if (docIds.size() > 0 && docIds.get(docIds.size() - 1) >= docId) {
                throw new IllegalArgumentException("doc ids must be added in increasing order");
            }
            docIds.add(docId);
            freqs.add(freq);
        }

        FieldPostingList build() {
            return new FieldPostingList(docIds.toArray(), freqs.toArray());
        }
    }
}
