package dev.sift.engine.index;

import java.util.Collection;
import java.util.Map;

/**
 * term → posting list, plus the document table. Built by {@link IndexBuilder}; read-only afterwards.
 */
public final class InvertedIndex {

    private final Map<String, PostingList> postings;
    private final DocTable docs;

    InvertedIndex(Map<String, PostingList> postings, DocTable docs) {
        this.postings = postings;
        this.docs = docs;
    }

    /** The posting list of a term, or {@code null} if no document contains it. */
    public PostingList postings(String term) {
        return postings.get(term);
    }

    /** n: how many documents contain the term (0 if none). */
    public int documentFrequency(String term) {
        PostingList list = postings.get(term);
        return list == null ? 0 : list.size();
    }

    public DocTable docs() {
        return docs;
    }

    /** Number of unique terms (the size of the dictionary / vocabulary). */
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

    /** Bytes of posting data (doc ids + freqs + positions, 4 bytes each) for all terms. */
    public long postingDataBytes() {
        long total = 0;
        for (PostingList list : postings.values()) {
            total += list.dataBytes();
        }
        return total;
    }

    /** Bytes of posting data for the given terms only (used to measure what stop words cost). */
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
}
