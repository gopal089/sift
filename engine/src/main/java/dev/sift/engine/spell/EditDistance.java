package dev.sift.engine.spell;

/**
 * Edit distance: the smallest number of single-letter changes that turn one word into another.
 * Computed with dynamic programming (DP): fill a table where cell (i, j) is the distance between the
 * first i letters of {@code a} and the first j letters of {@code b}.
 */
public final class EditDistance {

    private EditDistance() {
    }

    /**
     * Levenshtein distance: insert, delete or replace a letter, each costs 1.
     * <pre>
     *        ""  c  a  t
     *    ""   0  1  2  3
     *    c    1  0  1  2        cell = min( left + 1      (insert),
     *    u    2  1  1  2                    above + 1     (delete),
     *    t    3  2  2  1                    diagonal + (letters differ ? 1 : 0) )
     * </pre>
     * "cut" → "cat" = 1. Only the previous row is needed, so memory is O(length of b). Time O(|a| × |b|).
     */
    public static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;   // from "" to the first j letters of b: j inserts
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;    // from the first i letters of a to "": i deletes
            for (int j = 1; j <= b.length(); j++) {
                int replace = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(replace, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /**
     * Like {@link #levenshtein}, but swapping two neighbouring letters also costs 1 ("pyhton" → "python").
     * Swaps are the most common typing mistake; plain Levenshtein counts them as 2.
     * Needs the row before the previous one as well, to look back two letters.
     */
    public static int withSwaps(String a, String b) {
        int[] beforePrevious = new int[b.length() + 1];
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int replace = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                int best = Math.min(replace, Math.min(previous[j] + 1, current[j - 1] + 1));
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    best = Math.min(best, beforePrevious[j - 2] + 1);   // the last two letters are swapped
                }
                current[j] = best;
            }
            int[] swap = beforePrevious;
            beforePrevious = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
