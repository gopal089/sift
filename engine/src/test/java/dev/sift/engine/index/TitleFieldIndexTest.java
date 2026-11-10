package dev.sift.engine.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.sift.engine.index.IndexBuilder.AliasWords;
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
        assertEquals(1, docs.titleFieldLength(0));
        assertEquals(5, docs.bodyLength(0));
        assertEquals(6, docs.length(0));
        assertEquals(2, docs.titleFieldLength(1));
        assertEquals(2, docs.bodyLength(1));
        assertEquals(1.5, docs.averageTitleFieldLength(), 1e-9);
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
    void allAliasWordsAreAddedAfterTheBodyAndCountInTheTitleField() {
        IndexBuilder b = new IndexBuilder(AliasWords.ALL);
        // title: unit(0) state(1) | body: countri(2) | aliases: usa(3) usa(4) unit(5) state(6) america(7)
        b.addDocument(1, "United States", "Country", List.of("USA", "Usa", "United States of America"));
        InvertedIndex index = b.build();
        DocTable docs = index.docs();

        // "of" is a stop word but stays in the index (as everywhere): aliases add usa, usa, unit, state, of, america
        assertEquals(2, docs.titleLength(0));
        assertEquals(8, docs.titleFieldLength(0));
        assertEquals(1, docs.bodyLength(0));
        assertEquals(2, titleFreq(index, "usa", 0));
        assertEquals(2, titleFreq(index, "unit", 0));
        assertArrayEquals(new int[] {3, 4}, index.postings("usa").positions(0));
    }

    @Test
    void newOnlyAddsEachNewAliasWordOnce() {
        IndexBuilder b = new IndexBuilder(AliasWords.NEW_ONLY);
        b.addDocument(1, "United States", "Country", List.of("USA", "Usa", "United States of America"));
        InvertedIndex index = b.build();
        DocTable docs = index.docs();

        // new words only: usa, of, america (unit and state are already in the title; the 2nd "usa" is a repeat)
        assertEquals(5, docs.titleFieldLength(0));
        assertEquals(1, titleFreq(index, "usa", 0));
        assertEquals(1, titleFreq(index, "unit", 0));
        assertEquals(1, titleFreq(index, "america", 0));
    }
}
