package dev.sift.engine.store;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * All terms of the index, sorted, with where each term's posting blocks start in the three postings files.
 *
 * <p>Instead of a {@code HashMap<String, ...>} (one String, one entry and one value object per term:
 * roughly 100+ bytes each for 619k terms), everything is kept in a few flat arrays:
 * <pre>
 *   termBytes = "catdogzebra"           all terms as UTF-8, one after another
 *   termStart = [0, 3, 6, 11]           term i is termBytes[termStart[i] .. termStart[i+1])
 *   main / title / alias = one file offset per term (-1 = the term has no block in that file)
 * </pre>
 * Lookup is a <b>binary search</b> over the sorted terms: O(log V) comparisons, about 20 for 619k terms.
 * Terms are ordered by their UTF-8 bytes (compared as unsigned numbers), the same order the writer used.
 */
public final class TermDictionary {

    private final byte[] termBytes;
    private final int[] termStart;
    private final long[] mainOffset;
    private final long[] titleOffset;
    private final long[] aliasOffset;

    TermDictionary(byte[] termBytes, int[] termStart, long[] mainOffset, long[] titleOffset, long[] aliasOffset) {
        this.termBytes = termBytes;
        this.termStart = termStart;
        this.mainOffset = mainOffset;
        this.titleOffset = titleOffset;
        this.aliasOffset = aliasOffset;
    }

    /**
     * Reads {@code dictionary.bin}: an int V, then for each term a varint length, the UTF-8 bytes, and
     * three 8-byte offsets (main, title, alias).
     */
    static TermDictionary read(ByteBuffer in) {
        int count = in.getInt();
        int[] start = new int[count + 1];
        long[] main = new long[count];
        long[] title = new long[count];
        long[] alias = new long[count];
        byte[] bytes = new byte[in.remaining() - count * 24];   // an upper bound; trimmed below
        int used = 0;
        for (int i = 0; i < count; i++) {
            int length = VarInt.read(in);
            start[i] = used;
            in.get(bytes, used, length);
            used += length;
            main[i] = in.getLong();
            title[i] = in.getLong();
            alias[i] = in.getLong();
        }
        start[count] = used;
        return new TermDictionary(Arrays.copyOf(bytes, used), start, main, title, alias);
    }

    public int size() {
        return mainOffset.length;
    }

    /** The index of {@code term} in the sorted dictionary, or -1 if it is not there. */
    public int find(String term) {
        byte[] key = term.getBytes(StandardCharsets.UTF_8);
        int lo = 0;
        int hi = size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = Arrays.compareUnsigned(termBytes, termStart[mid], termStart[mid + 1], key, 0, key.length);
            if (cmp == 0) {
                return mid;
            }
            if (cmp < 0) {
                lo = mid + 1;   // the middle term is smaller than the key: look in the upper half
            } else {
                hi = mid - 1;
            }
        }
        return -1;
    }

    public String term(int i) {
        return new String(termBytes, termStart[i], termStart[i + 1] - termStart[i], StandardCharsets.UTF_8);
    }

    long mainOffset(int i) {
        return mainOffset[i];
    }

    long titleOffset(int i) {
        return titleOffset[i];
    }

    long aliasOffset(int i) {
        return aliasOffset[i];
    }
}
