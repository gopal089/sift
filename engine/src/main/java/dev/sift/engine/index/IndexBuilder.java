package dev.sift.engine.index;

import dev.sift.engine.text.Tokenizer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an {@link InvertedIndex} in memory, one document at a time.
 *
 * <p>For each document: tokenize "title + text", group the positions of each term,
 * then append (docId, tf, positions) to that term's posting list. Because documents
 * arrive in increasing doc id order, every posting list is automatically sorted.
 */
public final class IndexBuilder {

    private final Map<String, PostingList.Builder> terms = new HashMap<>();
    private final DocTable.Builder docs = new DocTable.Builder();

    /** Adds a document and returns its internal doc id (0, 1, 2, ...). */
    public int addDocument(int wikiId, String title, String text) {
        int docId = docs.size();

        // The title is indexed as the first words of the document.
        List<String> tokens = Tokenizer.tokenize(title);
        int titleLength = tokens.size();
        List<String> body = Tokenizer.tokenize(text);

        // term → positions inside this one document
        Map<String, IntArrayList> local = new HashMap<>();
        for (int pos = 0; pos < titleLength; pos++) {
            local.computeIfAbsent(tokens.get(pos), t -> new IntArrayList(2)).add(pos);
        }
        for (int i = 0; i < body.size(); i++) {
            local.computeIfAbsent(body.get(i), t -> new IntArrayList(2)).add(titleLength + i);
        }

        for (Map.Entry<String, IntArrayList> e : local.entrySet()) {
            terms.computeIfAbsent(e.getKey(), t -> new PostingList.Builder()).add(docId, e.getValue());
        }
        docs.add(wikiId, title, titleLength + body.size());
        return docId;
    }

    public InvertedIndex build() {
        Map<String, PostingList> postings = new HashMap<>(terms.size() * 2);
        for (Map.Entry<String, PostingList.Builder> e : terms.entrySet()) {
            postings.put(e.getKey(), e.getValue().build());
        }
        terms.clear(); // let the growable builder arrays be garbage-collected
        return new InvertedIndex(postings, docs.build());
    }
}
