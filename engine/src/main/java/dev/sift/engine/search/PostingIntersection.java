package dev.sift.engine.search;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** AND logic: which documents appear in every posting list. All inputs must be sorted ascending. */
public final class PostingIntersection {

    private PostingIntersection() {
    }

    /**
     * Two-pointer intersection of two sorted arrays. O(a + b) time.
     * <pre>
     *   a = [1, 3, 5, 9]      i→
     *   b = [3, 4, 9, 12]     j→
     *   1&lt;3: i++   3=3: keep 3   5&gt;4: j++   5&lt;9: i++   9=9: keep 9   → [3, 9]
     * </pre>
     */
    public static int[] intersect(int[] a, int[] b) {
        int[] out = new int[Math.min(a.length, b.length)];
        int i = 0;
        int j = 0;
        int n = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                out[n++] = a[i];
                i++;
                j++;
            } else if (a[i] < b[j]) {
                i++;
            } else {
                j++;
            }
        }
        return Arrays.copyOf(out, n);
    }

    /**
     * Intersects many lists, starting with the shortest ones so the running result stays small,
     * and stopping as soon as it is empty.
     */
    public static int[] intersectAll(List<int[]> lists) {
        if (lists.isEmpty()) {
            return new int[0];
        }
        int[][] sorted = lists.toArray(new int[0][]);
        Arrays.sort(sorted, Comparator.comparingInt(list -> list.length));
        int[] result = sorted[0];
        for (int i = 1; i < sorted.length && result.length > 0; i++) {
            result = intersect(result, sorted[i]);
        }
        return result;
    }
}
