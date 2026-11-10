package dev.sift.engine.index;

import dev.sift.engine.text.Tokenizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds an {@link InvertedIndex} in memory, one document at a time.
 *
 * <p>For each document we tokenize three things: the title, the body text, and (optionally) the
 * article's redirect titles ("aliases", e.g. "USA" for United States). Then:
 * <ul>
 *   <li><b>main postings</b>: every token of every field, with positions laid out as
 *       {@code [title][body][aliases]}. Used for matching (AND/OR), document frequency and snippets.</li>
 *   <li><b>title-field postings</b>: how often each term is in title + aliases. BM25F scores this field
 *       separately; the body count is simply main count − title-field count.</li>
 * </ul>
 * Documents arrive in increasing doc id order, so every posting list is sorted without a sorting step.
 */
public final class IndexBuilder {

    /** How alias (redirect title) words are added to the title field. */
    public enum AliasWords {
        /** Every word of every alias, repeats included ("USA", "Usa", "U.S.A." all add "usa"). */
        ALL,
        /** Only words not yet in the title field, each once. Keeps many near-duplicate aliases from bloating it. */
        NEW_ONLY
    }

    private final Map<String, PostingList.Builder> terms = new HashMap<>();
    private final Map<String, FieldPostingList.Builder> titleTerms = new HashMap<>();
    private final DocTable.Builder docs = new DocTable.Builder();
    private final AliasWords aliasWords;

    public IndexBuilder() {
        this(AliasWords.ALL);
    }

    public IndexBuilder(AliasWords aliasWords) {
        this.aliasWords = aliasWords;
    }

    /** Adds a document without aliases and returns its internal doc id (0, 1, 2, ...). */
    public int addDocument(int wikiId, String title, String text) {
        return addDocument(wikiId, title, text, List.of());
    }

    /** Adds a document whose title field also contains the words of {@code aliases}. */
    public int addDocument(int wikiId, String title, String text, List<String> aliases) {
        int docId = docs.size();

        List<String> titleTokens = Tokenizer.tokenize(title);
        List<String> body = Tokenizer.tokenize(text);
        List<String> aliasTokens = aliasTokens(titleTokens, aliases);

        // term → positions inside this one document, in the order [title][body][aliases]
        Map<String, IntArrayList> local = new HashMap<>();
        int pos = 0;
        for (String t : titleTokens) {
            local.computeIfAbsent(t, x -> new IntArrayList(2)).add(pos++);
        }
        for (String t : body) {
            local.computeIfAbsent(t, x -> new IntArrayList(2)).add(pos++);
        }
        for (String t : aliasTokens) {
            local.computeIfAbsent(t, x -> new IntArrayList(2)).add(pos++);
        }
        for (Map.Entry<String, IntArrayList> e : local.entrySet()) {
            terms.computeIfAbsent(e.getKey(), x -> new PostingList.Builder()).add(docId, e.getValue());
        }

        // term → count inside the title field (title + aliases)
        Map<String, Integer> titleFieldCounts = new HashMap<>();
        for (String t : titleTokens) {
            titleFieldCounts.merge(t, 1, Integer::sum);
        }
        for (String t : aliasTokens) {
            titleFieldCounts.merge(t, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : titleFieldCounts.entrySet()) {
            titleTerms.computeIfAbsent(e.getKey(), x -> new FieldPostingList.Builder()).add(docId, e.getValue());
        }

        docs.add(wikiId, title, titleTokens.size(), aliasTokens.size(), body.size());
        return docId;
    }

    private List<String> aliasTokens(List<String> titleTokens, List<String> aliases) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>(titleTokens);
        for (String alias : aliases) {
            for (String t : Tokenizer.tokenize(alias)) {
                if (aliasWords == AliasWords.ALL || seen.add(t)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    public InvertedIndex build() {
        Map<String, PostingList> postings = new HashMap<>(terms.size() * 2);
        for (Map.Entry<String, PostingList.Builder> e : terms.entrySet()) {
            postings.put(e.getKey(), e.getValue().build());
        }
        terms.clear(); // let the growable builder arrays be garbage-collected
        Map<String, FieldPostingList> titlePostings = new HashMap<>(titleTerms.size() * 2);
        for (Map.Entry<String, FieldPostingList.Builder> e : titleTerms.entrySet()) {
            titlePostings.put(e.getKey(), e.getValue().build());
        }
        titleTerms.clear();
        return new InvertedIndex(postings, titlePostings, docs.build());
    }
}
