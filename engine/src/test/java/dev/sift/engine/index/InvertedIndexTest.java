package dev.sift.engine.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvertedIndexTest {

    private InvertedIndex index;

    @BeforeEach
    void buildTinyIndex() {
        IndexBuilder builder = new IndexBuilder();
        // tokens: cat(0) the(1) cat(2) sat(3) on(4) the(5) mat(6)                       → length 7
        assertEquals(0, builder.addDocument(100, "Cats", "The cat sat on the mat."));
        // tokens: dog(0) a(1) dog(2) chase(3) the(4) cat(5)                             → length 6
        assertEquals(1, builder.addDocument(200, "Dogs", "A dog chased the cat."));
        // tokens: bird(0) bird(1) fly(2)                                                → length 3
        assertEquals(2, builder.addDocument(300, "Birds", "Birds fly"));
        index = builder.build();
    }

    @Test
    void postingsAreSortedWithFrequenciesAndPositions() {
        PostingList cat = index.postings("cat");
        assertArrayEquals(new int[] {0, 1}, cat.docIds());
        assertEquals(2, cat.freq(0));            // title "Cats" + "cat" in the text
        assertEquals(1, cat.freq(1));
        assertArrayEquals(new int[] {0, 2}, cat.positions(0));
        assertArrayEquals(new int[] {5}, cat.positions(1));
    }

    @Test
    void stopWordsAreIndexed() {
        PostingList the = index.postings("the");
        assertArrayEquals(new int[] {0, 1}, the.docIds());
        assertEquals(2, the.freq(0));
        assertArrayEquals(new int[] {1, 5}, the.positions(0));
        assertEquals(1, index.documentFrequency("a"));
    }

    @Test
    void unknownTermHasNoPostings() {
        assertNull(index.postings("zebra"));
        assertEquals(0, index.documentFrequency("zebra"));
    }

    @Test
    void docTableHoldsIdsTitlesAndLengths() {
        DocTable docs = index.docs();
        assertEquals(3, docs.size());
        assertEquals(200, docs.wikiId(1));
        assertEquals("Birds", docs.title(2));
        assertEquals(7, docs.length(0));
        assertEquals(6, docs.length(1));
        assertEquals(3, docs.length(2));
        assertEquals(16 / 3.0, docs.averageLength(), 1e-9);
    }

    @Test
    void countsAndSizes() {
        // terms: cat the sat on mat dog a chase bird fly
        assertEquals(10, index.termCount());
        assertEquals(16, index.positionCount());          // = total tokens = 7 + 6 + 3
        // postings: cat×2, the×2, sat, on, mat, dog, a, chase, bird, fly = 12
        assertEquals(12, index.postingCount());
        // each posting has a doc id and a freq; plus one int per position
        assertEquals(4L * (12 + 12 + 16), index.postingDataBytes());
        assertEquals(4L * (2 + 2 + 3) + 4L * (1 + 1 + 1), index.postingDataBytes(java.util.List.of("the", "a")));
    }

    @Test
    void postingBuilderRejectsOutOfOrderDocIds() {
        PostingList.Builder b = new PostingList.Builder();
        IntArrayList pos = new IntArrayList();
        pos.add(0);
        b.add(5, pos);
        assertThrows(IllegalArgumentException.class, () -> b.add(5, pos));
    }
}
