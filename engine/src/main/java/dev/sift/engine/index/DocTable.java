package dev.sift.engine.index;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-document facts, indexed by our internal doc id (0, 1, 2, ... in file order).
 * BM25 needs each document's length and the average length.
 */
public final class DocTable {

    private final int[] wikiIds;
    private final String[] titles;
    private final int[] lengths;
    private final double averageLength;

    private DocTable(int[] wikiIds, String[] titles, int[] lengths) {
        this.wikiIds = wikiIds;
        this.titles = titles;
        this.lengths = lengths;
        long total = 0;
        for (int len : lengths) {
            total += len;
        }
        this.averageLength = lengths.length == 0 ? 0 : (double) total / lengths.length;
    }

    /** Number of documents, N. */
    public int size() {
        return lengths.length;
    }

    public int wikiId(int docId) {
        return wikiIds[docId];
    }

    public String title(int docId) {
        return titles[docId];
    }

    /** Number of tokens (title + text, stop words included) in the document. */
    public int length(int docId) {
        return lengths[docId];
    }

    public double averageLength() {
        return averageLength;
    }

    static final class Builder {
        private final IntArrayList wikiIds = new IntArrayList(1024);
        private final List<String> titles = new ArrayList<>();
        private final IntArrayList lengths = new IntArrayList(1024);

        int add(int wikiId, String title, int length) {
            wikiIds.add(wikiId);
            titles.add(title);
            lengths.add(length);
            return lengths.size() - 1;
        }

        int size() {
            return lengths.size();
        }

        DocTable build() {
            return new DocTable(wikiIds.toArray(), titles.toArray(new String[0]), lengths.toArray());
        }
    }
}
