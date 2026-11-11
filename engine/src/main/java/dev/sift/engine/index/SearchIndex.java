package dev.sift.engine.index;

/**
 * What the {@link dev.sift.engine.search.Searcher} needs from an index. Two implementations:
 * {@link InvertedIndex} (everything in Java memory) and {@link dev.sift.engine.store.DiskIndex}
 * (memory-mapped files). Because both give the same answers, the same search code runs on either.
 */
public interface SearchIndex {

    /** The posting list of a term (all fields), or {@code null} if no document contains it. */
    PostingList postings(String term);

    /** The term's postings in titles, or {@code null} if it is in no title. */
    FieldPostingList titlePostings(String term);

    /** The term's postings in alias fields, or {@code null} if it is in none. */
    FieldPostingList aliasPostings(String term);

    DocTable docs();
}
