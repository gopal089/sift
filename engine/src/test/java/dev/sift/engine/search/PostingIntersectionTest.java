package dev.sift.engine.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class PostingIntersectionTest {

    @Test
    void intersectsTwoSortedLists() {
        assertArrayEquals(new int[] {3, 9}, PostingIntersection.intersect(new int[] {1, 3, 5, 9}, new int[] {3, 4, 9, 12}));
    }

    @Test
    void edgeCases() {
        assertArrayEquals(new int[] {}, PostingIntersection.intersect(new int[] {}, new int[] {1, 2}));
        assertArrayEquals(new int[] {}, PostingIntersection.intersect(new int[] {1, 3}, new int[] {2, 4}));
        assertArrayEquals(new int[] {1, 2, 3}, PostingIntersection.intersect(new int[] {1, 2, 3}, new int[] {1, 2, 3}));
        assertArrayEquals(new int[] {7}, PostingIntersection.intersect(new int[] {7}, new int[] {0, 7, 100}));
    }

    @Test
    void intersectsManyListsInAnyOrder() {
        int[] a = {1, 2, 3, 4, 5, 6, 7, 8};
        int[] b = {2, 4, 6, 8};
        int[] c = {4, 8, 9};
        assertArrayEquals(new int[] {4, 8}, PostingIntersection.intersectAll(List.of(a, b, c)));
        assertArrayEquals(new int[] {4, 8}, PostingIntersection.intersectAll(List.of(c, a, b)));
        assertArrayEquals(new int[] {}, PostingIntersection.intersectAll(List.of(a, new int[] {}, c)));
        assertArrayEquals(new int[] {}, PostingIntersection.intersectAll(List.of()));
        assertArrayEquals(b, PostingIntersection.intersectAll(List.of(b)));
    }

    /** Compare against a simple, obviously-correct set intersection on random data. */
    @Test
    void agreesWithSetIntersectionOnRandomLists() {
        Random random = new Random(42);
        for (int round = 0; round < 200; round++) {
            TreeSet<Integer> x = randomSet(random);
            TreeSet<Integer> y = randomSet(random);
            TreeSet<Integer> expected = new TreeSet<>(x);
            expected.retainAll(y);
            assertArrayEquals(toArray(expected), PostingIntersection.intersect(toArray(x), toArray(y)));
        }
    }

    private static TreeSet<Integer> randomSet(Random random) {
        TreeSet<Integer> set = new TreeSet<>();
        int n = random.nextInt(50);
        for (int i = 0; i < n; i++) {
            set.add(random.nextInt(100));
        }
        return set;
    }

    private static int[] toArray(TreeSet<Integer> set) {
        return set.stream().mapToInt(Integer::intValue).toArray();
    }
}
