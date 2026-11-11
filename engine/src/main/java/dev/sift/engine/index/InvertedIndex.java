package dev.sift.engine.index;

import java.util.Collection;
import java.util.Map;

/**
 * The search index: term → main posting list, term → title postings, term → alias postings, plus the
 * document table. Built by {@link IndexBuilder} (in memory) or read from disk; read-only afterwards.
 */
public final class InvertedIndex implements SearchIndex {

    private final Map<String, PostingList> postings;
    private final Map<String, FieldPostingList> titlePostings;
    private final Map<String, FieldPostingList> aliasPostings;
    private final DocTable docs;
    private final Vocabulary vocabulary;

    public InvertedIndex(Map<String, PostingList> postings, Map<String, FieldPostingList> titlePostings,
                         Map<String, FieldPostingList> aliasPostings, DocTable docs, Vocabulary vocabulary) {
        this.vocabulary = vocabulary;
        this.postings = postings;
        this.titlePostings = titlePostings;
        this.aliasPostings = aliasPostings;
        this.docs = docs;
    }

    /** The posting list of a term (all fields), or {@code null} if no document contains it. */
    @Override
    public PostingList postings(String term) {
        return postings.get(term);
    }

    /** The term's postings in titles, or {@code null} if it is in no title. */
    @Override
    public FieldPostingList titlePostings(String term) {
        return titlePostings.get(term);
    }

    /** The term's postings in alias fields (freq is always 1), or {@code null} if it is in none. */
    @Override
    public FieldPostingList aliasPostings(String term) {
        return aliasPostings.get(term);
    }

    /** All terms. Used to write the index to disk and to build the autocomplete trie later. */
    public Collection<String> terms() {
        return postings.keySet();
    }

    /** n: how many documents contain the term (0 if none). */
    public int documentFrequency(String term) {
        PostingList list = postings.get(term);
        return list == null ? 0 : list.size();
    }

    @Override
    public DocTable docs() {
        return docs;
    }

    /** The real (unstemmed) words for spelling suggestions. */
    public Vocabulary vocabulary() {
        return vocabulary;
    }

    /** Number of unique terms (the size of the dictionary). */
    public int termCount() {
        return postings.size();
    }

    /** Total number of (term, document) postings. */
    public long postingCount() {
        long total = 0;
        for (PostingList list : postings.values()) {
            total += list.size();
        }
        return total;
    }

    /** Total number of stored positions = total tokens indexed. */
    public long positionCount() {
        long total = 0;
        for (PostingList list : postings.values()) {
            total += list.positionCount();
        }
        return total;
    }

    /** Bytes of main posting data (doc ids + freqs + positions, 4 bytes each) for all terms. */
    public long postingDataBytes() {
        long total = 0;
        for (PostingList list : postings.values()) {
            total += list.dataBytes();
        }
        return total;
    }

    /** Bytes of main posting data for the given terms only (used to measure what stop words cost). */
    public long postingDataBytes(Collection<String> terms) {
        long total = 0;
        for (String term : terms) {
            PostingList list = postings.get(term);
            if (list != null) {
                total += list.dataBytes();
            }
        }
        return total;
    }

    public int titleTermCount() {
        return titlePostings.size();
    }

    public int aliasTermCount() {
        return aliasPostings.size();
    }

    public long titlePostingCount() {
        return count(titlePostings);
    }

    public long aliasPostingCount() {
        return count(aliasPostings);
    }

    private static long count(Map<String, FieldPostingList> field) {
        long total = 0;
        for (FieldPostingList list : field.values()) {
            total += list.size();
        }
        return total;
    }
}
