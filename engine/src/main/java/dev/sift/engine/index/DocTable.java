package dev.sift.engine.index;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-document facts, indexed by our internal doc id (0, 1, 2, ... in file order).
 *
 * <p>A document has three <b>fields</b>:
 * <ul>
 *   <li><b>title</b>: the article title's words</li>
 *   <li><b>body</b>: the article text</li>
 *   <li><b>alias</b>: the distinct words of redirect titles ("USA" → United States), each once</li>
 * </ul>
 * Word positions in the main posting lists are laid out as {@code [title][body][alias]}: the body starts at
 * position {@link #titleLength(int)} and ends before {@code titleLength + bodyLength}.
 */
public final class DocTable {

    private final int[] wikiIds;
    private final String[] titles;
    private final int[] titleLengths;
    private final int[] bodyLengths;
    private final int[] aliasLengths;
    private final double averageLength;
    private final double averageTitleLength;
    private final double averageBodyLength;

    /** Built by {@link Builder}, or by the on-disk index reader from stored arrays. */
    public DocTable(int[] wikiIds, String[] titles, int[] titleLengths, int[] bodyLengths, int[] aliasLengths) {
        this.wikiIds = wikiIds;
        this.titles = titles;
        this.titleLengths = titleLengths;
        this.bodyLengths = bodyLengths;
        this.aliasLengths = aliasLengths;
        long title = 0;
        long body = 0;
        long alias = 0;
        for (int d = 0; d < titles.length; d++) {
            title += titleLengths[d];
            body += bodyLengths[d];
            alias += aliasLengths[d];
        }
        int n = titles.length;
        this.averageTitleLength = n == 0 ? 0 : (double) title / n;
        this.averageBodyLength = n == 0 ? 0 : (double) body / n;
        this.averageLength = n == 0 ? 0 : (double) (title + body + alias) / n;
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

    /** All tokens of the document (title + body + alias), stop words included. Used by single-field BM25. */
    public int length(int docId) {
        return titleLengths[docId] + bodyLengths[docId] + aliasLengths[docId];
    }

    /** Tokens in the title. The body's first position equals this number. */
    public int titleLength(int docId) {
        return titleLengths[docId];
    }

    public int bodyLength(int docId) {
        return bodyLengths[docId];
    }

    /** Distinct alias words of the document (0 if it has no redirects or the index was built without them). */
    public int aliasLength(int docId) {
        return aliasLengths[docId];
    }

    public double averageLength() {
        return averageLength;
    }

    public double averageTitleLength() {
        return averageTitleLength;
    }

    public double averageBodyLength() {
        return averageBodyLength;
    }

    static final class Builder {
        private final IntArrayList wikiIds = new IntArrayList(1024);
        private final List<String> titles = new ArrayList<>();
        private final IntArrayList titleLengths = new IntArrayList(1024);
        private final IntArrayList bodyLengths = new IntArrayList(1024);
        private final IntArrayList aliasLengths = new IntArrayList(1024);

        int add(int wikiId, String title, int titleLength, int bodyLength, int aliasLength) {
            wikiIds.add(wikiId);
            titles.add(title);
            titleLengths.add(titleLength);
            bodyLengths.add(bodyLength);
            aliasLengths.add(aliasLength);
            return titles.size() - 1;
        }

        int size() {
            return titles.size();
        }

        DocTable build() {
            return new DocTable(wikiIds.toArray(), titles.toArray(new String[0]),
                    titleLengths.toArray(), bodyLengths.toArray(), aliasLengths.toArray());
        }
    }
}
