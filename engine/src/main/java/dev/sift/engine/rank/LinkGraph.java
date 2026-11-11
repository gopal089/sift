package dev.sift.engine.rank;

import dev.sift.engine.data.ArticleReader;
import dev.sift.engine.data.RedirectAliases;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who links to whom: for every article (by doc id) the doc ids of the articles it links to.
 *
 * <p>Stored compactly as two arrays ("compressed sparse row"):
 * <pre>
 *   targets  = [1, 2,  2,  0]      all link targets, grouped by source page
 *   outStart = [0, 2, 3, 3, 4]     page p's links are targets[outStart[p] .. outStart[p+1])
 *   → page 0 links to 1 and 2; page 1 links to 2; page 2 links to nothing (a "dangling" page); page 3 links to 0
 * </pre>
 */
public final class LinkGraph {

    private final int[] outStart;
    private final int[] targets;

    private LinkGraph(int[] outStart, int[] targets) {
        this.outStart = outStart;
        this.targets = targets;
    }

    /** Builds a graph from each page's list of targets. Duplicate links and links to itself are dropped. */
    public static LinkGraph of(List<int[]> linksPerPage) {
        int[] start = new int[linksPerPage.size() + 1];
        int[][] clean = new int[linksPerPage.size()][];
        for (int p = 0; p < linksPerPage.size(); p++) {
            clean[p] = distinctWithout(linksPerPage.get(p), p);
            start[p + 1] = start[p] + clean[p].length;
        }
        int[] all = new int[start[linksPerPage.size()]];
        for (int p = 0; p < clean.length; p++) {
            System.arraycopy(clean[p], 0, all, start[p], clean[p].length);
        }
        return new LinkGraph(start, all);
    }

    /** Sorted, without duplicates and without {@code self}. */
    private static int[] distinctWithout(int[] values, int self) {
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (sorted[i] != self && (n == 0 || sorted[i] != sorted[n - 1])) {
                sorted[n++] = sorted[i];
            }
        }
        return Arrays.copyOf(sorted, n);
    }

    /**
     * Reads {@code articles.jsonl} twice: first to learn every title's doc id, then to turn each article's
     * link titles into doc ids. A link to a redirect ("USA") is followed to its final article
     * ("United States"). Links whose target is not an article (red links, skipped pages) are dropped.
     */
    public static LinkGraph fromArticles(Path articles, RedirectAliases redirects) throws IOException {
        Map<String, Integer> docIdByTitle = new HashMap<>();
        ArticleReader.forEach(articles, a -> docIdByTitle.put(a.title(), docIdByTitle.size()));

        int[][] links = new int[docIdByTitle.size()][];
        int[] next = {0};
        ArticleReader.forEach(articles, a -> {
            List<String> titles = a.links() == null ? List.of() : a.links();
            int[] resolved = new int[titles.size()];
            int n = 0;
            for (String title : titles) {
                Integer target = docIdByTitle.get(title);
                if (target == null) {
                    String finalTitle = redirects.resolve(title);   // maybe it is a redirect
                    target = finalTitle == null ? null : docIdByTitle.get(finalTitle);
                }
                if (target != null) {
                    resolved[n++] = target;
                }
            }
            links[next[0]++] = Arrays.copyOf(resolved, n);
        });
        return of(Arrays.asList(links));
    }

    public int pageCount() {
        return outStart.length - 1;
    }

    public int linkCount() {
        return targets.length;
    }

    public int outDegree(int page) {
        return outStart[page + 1] - outStart[page];
    }

    /** The i-th link target of {@code page}, for i in [0, outDegree). */
    public int target(int page, int i) {
        return targets[outStart[page] + i];
    }

    /** How many pages have no outgoing links. */
    public int danglingCount() {
        int n = 0;
        for (int p = 0; p < pageCount(); p++) {
            if (outDegree(p) == 0) {
                n++;
            }
        }
        return n;
    }
}
