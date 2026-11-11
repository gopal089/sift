package dev.sift.engine.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class TitleFieldIndexTest {

    private static int titleFreq(InvertedIndex index, String term, int doc) {
        FieldPostingList list = index.titlePostings(term);
        for (int i = 0; list != null && i < list.size(); i++) {
            if (list.docId(i) == doc) {
                return list.freq(i);
            }
        }
        return 0;
    }

    private static int mainFreq(InvertedIndex index, String term, int doc) {
        PostingList list = index.postings(term);
        for (int i = 0; list != null && i < list.size(); i++) {
            if (list.docId(i) == doc) {
                return list.freq(i);
            }
        }
        return 0;
    }

    @Test
    void titleWordsGetTheirOwnPostingsAndLengths() {
        IndexBuilder b = new IndexBuilder();
        // title: cat(0) | body: cat(1) sat(2) on(3) the(4) mat(5)
        b.addDocument(100, "Cat", "Cat sat on the mat");
        // title: the(0) dog(1) | body: dog(2) bark(3)
        b.addDocument(200, "The Dog", "Dogs bark");
        InvertedIndex index = b.build();
        DocTable docs = index.docs();

        assertEquals(1, docs.titleLength(0));
        assertEquals(5, docs.bodyLength(0));
        assertEquals(0, docs.aliasLength(0));
        assertEquals(6, docs.length(0));
        assertEquals(2, docs.titleLength(1));
        assertEquals(2, docs.bodyLength(1));
        assertEquals(1.5, docs.averageTitleLength(), 1e-9);
        assertEquals(3.5, docs.averageBodyLength(), 1e-9);
        assertEquals(5.0, docs.averageLength(), 1e-9);

        // "cat": once in doc 0's title, twice in doc 0 overall → body count = 2 − 1 = 1
        assertEquals(1, titleFreq(index, "cat", 0));
        assertEquals(2, mainFreq(index, "cat", 0));
        // "the": in doc 1's title only; doc 0 has it only in the body
        assertEquals(0, titleFreq(index, "the", 0));
        assertEquals(1, titleFreq(index, "the", 1));
        // "sat" is in no title at all
        assertNull(index.titlePostings("sat"));
        // positions keep the [title][body] layout: the body of doc 1 starts at position 2
        PostingList dog = index.postings("dog");
        assertArrayEquals(new int[] {1, 2}, dog.positions(0));
    }

    @Test
    void aliasWordsGetTheirOwnFieldEachWordOnce() {
        IndexBuilder b = new IndexBuilder();
        // title: unit(0) state(1) | body: countri(2) | alias words, each once: usa(3) unit(4) state(5) of(6) america(7)
        b.addDocument(1, "United States", "Country", List.of("USA", "U.S.A. usa", "United States of America"));
        b.addDocument(2, "Canada", "Country north of the usa", List.of());
        InvertedIndex index = b.build();
        DocTable docs = index.docs();

        // "U.S.A." splits into u, s, a: three more alias words (positions 4, 5, 6), so 8 distinct alias words
        assertEquals(2, docs.titleLength(0));
        assertEquals(1, docs.bodyLength(0));
        assertEquals(8, docs.aliasLength(0));
        assertEquals(11, docs.length(0));
        assertEquals(0, docs.aliasLength(1));

        // "usa" appears in three aliases but counts once; it is not in the title
        FieldPostingList usa = index.aliasPostings("usa");
        assertEquals(1, usa.size());
        assertEquals(0, usa.docId(0));
        assertEquals(1, usa.freq(0));
        assertEquals(0, titleFreq(index, "usa", 0));
        // alias words are also in the main postings (so AND can match them), placed after the body
        assertEquals(1, mainFreq(index, "usa", 0));
        assertArrayEquals(new int[] {3}, index.postings("usa").positions(0));
        // "unit" is in the title once and among the alias words once: main count 2
        assertEquals(1, titleFreq(index, "unit", 0));
        assertEquals(2, mainFreq(index, "unit", 0));
        assertEquals(1, index.aliasPostings("unit").size());
        // a body word is in no alias field
        assertNull(index.aliasPostings("countri"));
    }
}
