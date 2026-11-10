package dev.sift.engine.index;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-document facts, indexed by our internal doc id (0, 1, 2, ... in file order).
 *
 * <p>A document has two <b>fields</b>, and BM25F needs each field's length and average length:
 * <ul>
 *   <li><b>title field</b> = the title's words + (optionally) words from redirect titles ("aliases")</li>
 *   <li><b>body field</b> = the article text</li>
 * </ul>
 * Word positions in the main posting lists are laid out as
 * {@code [title words][body words][alias words]}, so the body starts at {@link #titleLength(int)}.
 */
public final class DocTable {

    private final int[] wikiIds;
    private final String[] titles;
    private final int[] titleLengths;
    private final int[] aliasLengths;
    private final int[] bodyLengths;
    private final double averageLength;
    private final double averageTitleFieldLength;
    private final double averageBodyLength;

    private DocTable(int[] wikiIds, String[] titles, int[] titleLengths, int[] aliasLengths, int[] bodyLengths) {
        this.wikiIds = wikiIds;
        this.titles = titles;
        this.titleLengths = titleLengths;
        this.aliasLengths = aliasLengths;
        this.bodyLengths = bodyLengths;
        long titleField = 0;
        long body = 0;
        for (int d = 0; d < titles.length; d++) {
            titleField += titleLengths[d] + aliasLengths[d];
            body += bodyLengths[d];
        }
        int n = titles.length;
        this.averageTitleFieldLength = n == 0 ? 0 : (double) titleField / n;
        this.averageBodyLength = n == 0 ? 0 : (double) body / n;
        this.averageLength = n == 0 ? 0 : (double) (titleField + body) / n;
    }

    /** Number of documents, N. */
    public int size() {
        return titles.length;
    }

    public int wikiId(int docId) {
        return wikiIds[docId];
    }

    public String title(int docId) {
        return titles[docId];
    }

    /** All tokens of the document (title field + body), stop words included. Used by single-field BM25. */
    public int length(int docId) {
        return titleFieldLength(docId) + bodyLengths[docId];
    }

    /** Tokens of the title itself (no aliases). The body's first position equals this number. */
    public int titleLength(int docId) {
        return titleLengths[docId];
    }

    /** Tokens in the title field: title words + alias words. */
    public int titleFieldLength(int docId) {
        return titleLengths[docId] + aliasLengths[docId];
    }

    public int bodyLength(int docId) {
        return bodyLengths[docId];
    }

    public double averageLength() {
        return averageLength;
    }

    public double averageTitleFieldLength() {
        return averageTitleFieldLength;
    }

    public double averageBodyLength() {
        return averageBodyLength;
    }

    static final class Builder {
        private final IntArrayList wikiIds = new IntArrayList(1024);
        private final List<String> titles = new ArrayList<>();
        private final IntArrayList titleLengths = new IntArrayList(1024);
        private final IntArrayList aliasLengths = new IntArrayList(1024);
        private final IntArrayList bodyLengths = new IntArrayList(1024);

        int add(int wikiId, String title, int titleLength, int aliasLength, int bodyLength) {
            wikiIds.add(wikiId);
            titles.add(title);
            titleLengths.add(titleLength);
            aliasLengths.add(aliasLength);
            bodyLengths.add(bodyLength);
            return titles.size() - 1;
        }

        int size() {
            return titles.size();
        }

        DocTable build() {
            return new DocTable(wikiIds.toArray(), titles.toArray(new String[0]),
                    titleLengths.toArray(), aliasLengths.toArray(), bodyLengths.toArray());
        }
    }
}
