package dev.sift.engine.index;

import dev.sift.engine.text.Tokenizer;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds an {@link InvertedIndex} in memory, one document at a time.
 *
 * <p>For each document we tokenize three fields: the title, the body text, and the article's redirect
 * titles ("aliases", e.g. "USA" for United States). Then we fill three kinds of posting lists:
 * <ul>
 *   <li><b>main postings</b>: every token of every field, with positions laid out as
 *       {@code [title][body][alias]}. Used for matching (AND/OR), document frequency and snippets.</li>
 *   <li><b>title postings</b>: how often each term is in the title.</li>
 *   <li><b>alias postings</b>: which documents have the term among their alias words. Each distinct alias
 *       word counts once per document, however many redirects repeat it.</li>
 * </ul>
 * The body count of a term is simply main count − title count − alias count.
 * Documents arrive in increasing doc id order, so every posting list is sorted without a sorting step.
 */
public final class IndexBuilder {

    private final Map<String, PostingList.Builder> terms = new HashMap<>();
    private final Map<String, FieldPostingList.Builder> titleTerms = new HashMap<>();
    private final Map<String, FieldPostingList.Builder> aliasTerms = new HashMap<>();
    private final DocTable.Builder docs = new DocTable.Builder();

    /** Adds a document without aliases and returns its internal doc id (0, 1, 2, ...). */
    public int addDocument(int wikiId, String title, String text) {
        return addDocument(wikiId, title, text, List.of());
    }

    /** Adds a document whose alias field holds the distinct words of {@code aliases}. */
    public int addDocument(int wikiId, String title, String text, List<String> aliases) {
        int docId = docs.size();

        List<String> titleTokens = Tokenizer.tokenize(title);
        List<String> body = Tokenizer.tokenize(text);
        Set<String> aliasWords = new LinkedHashSet<>();   // a set: each word once, in first-seen order
        for (String alias : aliases) {
            aliasWords.addAll(Tokenizer.tokenize(alias));
        }

        // term → positions inside this one document, in the order [title][body][alias]
        Map<String, IntArrayList> local = new HashMap<>();
        int pos = 0;
        for (String t : titleTokens) {
            local.computeIfAbsent(t, x -> new IntArrayList(2)).add(pos++);
        }
        for (String t : body) {
            local.computeIfAbsent(t, x -> new IntArrayList(2)).add(pos++);
        }
        for (String t : aliasWords) {
            local.computeIfAbsent(t, x -> new IntArrayList(2)).add(pos++);
        }
        for (Map.Entry<String, IntArrayList> e : local.entrySet()) {
            terms.computeIfAbsent(e.getKey(), x -> new PostingList.Builder()).add(docId, e.getValue());
        }

        // term → count inside the title
        Map<String, Integer> titleCounts = new HashMap<>();
        for (String t : titleTokens) {
            titleCounts.merge(t, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : titleCounts.entrySet()) {
            titleTerms.computeIfAbsent(e.getKey(), x -> new FieldPostingList.Builder()).add(docId, e.getValue());
        }
        for (String t : aliasWords) {
            aliasTerms.computeIfAbsent(t, x -> new FieldPostingList.Builder()).add(docId, 1);
        }

        docs.add(wikiId, title, titleTokens.size(), body.size(), aliasWords.size());
        return docId;
    }

    public InvertedIndex build() {
        Map<String, PostingList> postings = new HashMap<>(terms.size() * 2);
        for (Map.Entry<String, PostingList.Builder> e : terms.entrySet()) {
            postings.put(e.getKey(), e.getValue().build());
        }
        terms.clear(); // let the growable builder arrays be garbage-collected
        Map<String, FieldPostingList> titlePostings = buildField(titleTerms);
        Map<String, FieldPostingList> aliasPostings = buildField(aliasTerms);
        return new InvertedIndex(postings, titlePostings, aliasPostings, docs.build());
    }

    private static Map<String, FieldPostingList> buildField(Map<String, FieldPostingList.Builder> builders) {
        Map<String, FieldPostingList> out = new HashMap<>(builders.size() * 2);
        for (Map.Entry<String, FieldPostingList.Builder> e : builders.entrySet()) {
            out.put(e.getKey(), e.getValue().build());
        }
        builders.clear();
        return out;
    }
}
