package dev.sift.engine.index;

import java.util.Arrays;

/**
 * A growable list of primitive ints. Java's {@code ArrayList<Integer>} would store every number
 * as a separate object (about 16 bytes each, plus a reference); this stores 4 bytes per number.
 */
final class IntArrayList {

    private int[] data;
    private int size;

    IntArrayList() {
        this(4);
    }

    IntArrayList(int initialCapacity) {
        data = new int[Math.max(1, initialCapacity)];
    }

    void add(int value) {
        if (size == data.length) {
            data = Arrays.copyOf(data, data.length * 2); // double when full: amortised O(1) per add
        }
        data[size++] = value;
    }

    int get(int index) {
        return data[index];
    }

    int size() {
        return size;
    }

    /** A copy with exactly {@code size} elements (no spare capacity). */
    int[] toArray() {
        return Arrays.copyOf(data, size);
    }
}
