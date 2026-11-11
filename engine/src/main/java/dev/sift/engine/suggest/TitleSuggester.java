package dev.sift.engine.suggest;

import dev.sift.engine.index.DocTable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Simple title autocomplete for Phase 2: titles that start with what the user typed, alphabetically.
 *
 * <p>All doc ids are sorted by lowercase title once. A prefix query is a <b>binary search</b> for the first
 * title that is &gt;= the prefix, then we walk forward while titles still start with it:
 * O(log N) to find the spot + O(results). Phase 4 replaces this with a trie that ranks by popularity.
 */
public final class TitleSuggester {

    private final DocTable docs;
    private final int[] byTitle;   // doc ids, ordered by lowercase title

    public TitleSuggester(DocTable docs) {
        this.docs = docs;
        String[] lower = new String[docs.size()];
        Integer[] order = new Integer[docs.size()];
        for (int d = 0; d < docs.size(); d++) {
            lower[d] = normalize(docs.title(d));
            order[d] = d;
        }
        Arrays.sort(order, Comparator.comparing((Integer d) -> lower[d]).thenComparing(d -> d));
        this.byTitle = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            byTitle[i] = order[i];
        }
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** Up to {@code limit} titles starting with {@code prefix} (case-insensitive), in alphabetical order. */
    public List<String> suggest(String prefix, int limit) {
        String p = normalize(prefix.strip());
        List<String> out = new ArrayList<>();
        if (p.isEmpty()) {
            return out;
        }
        // lower bound: the first index whose title is >= the prefix
        int lo = 0;
        int hi = byTitle.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (normalize(docs.title(byTitle[mid])).compareTo(p) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        for (int i = lo; i < byTitle.length && out.size() < limit; i++) {
            String title = docs.title(byTitle[i]);
            if (!normalize(title).startsWith(p)) {
                break;   // sorted order: once a title does not start with the prefix, none after it will
            }
            out.add(title);
        }
        return out;
    }
}
