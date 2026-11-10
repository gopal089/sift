package dev.sift.engine.search;

/** A document id with its score. */
public record ScoredDoc(int docId, double score) {
}
