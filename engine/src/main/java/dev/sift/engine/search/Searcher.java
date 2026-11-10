package dev.sift.engine.search;

import dev.sift.engine.index.DocTable;
import dev.sift.engine.index.FieldPostingList;
import dev.sift.engine.index.InvertedIndex;
import dev.sift.engine.index.PostingList;
import dev.sift.engine.search.SearchResult.Hit;
import dev.sift.engine.search.SearchResult.MatchMode;
import dev.sift.engine.text.Tokenizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Keyword search: tokenize the query, find matching documents, score them, keep the top K.
 *
 * <ol>
 *   <li><b>AND</b> first: only documents that contain every query term (in any field).</li>
 *   <li><b>OR</b> fallback if AND finds nothing (or a term is not in the index): any term may match.</li>
 * </ol>
 * Scoring follows a {@link Ranking}: single-field BM25 (baseline), BM25F (title + body), or the sum variant.
 */
public final class Searcher {

    private final InvertedIndex index;
    private final DocTable docs;
    private final Ranking ranking;
    private final Bm25 bm25;          // single field: title + body together
    private final Bm25F bm25f;        // two fields
    private final Bm25 bodyBm25;      // the "sum" variant: body ...
    private final Bm25 titleBm25;     // ... and title, each saturated separately

    public Searcher(InvertedIndex index) {
        this(index, Ranking.DEFAULT);
    }

    public Searcher(InvertedIndex index, Ranking ranking) {
        this.index = index;
        this.docs = index.docs();
        this.ranking = ranking;
        this.bm25 = new Bm25(docs.size(), docs.averageLength(), ranking.k1(), ranking.bBody());
        this.bm25f = new Bm25F(ranking.k1(), ranking.wTitle(), ranking.bTitle(), ranking.bBody(),
                docs.averageTitleFieldLength(), docs.averageBodyLength());
        this.bodyBm25 = new Bm25(docs.size(), docs.averageBodyLength(), ranking.k1(), ranking.bBody());
        this.titleBm25 = new Bm25(docs.size(), docs.averageTitleFieldLength(), ranking.k1(), ranking.bTitle());
    }

    /** One line describing the ranking settings, written into every eval results file. */
    public String description() {
        return ranking.toString();
    }

    public SearchResult search(String query, int k) {
        List<String> terms = Tokenizer.queryTerms(query);
        if (terms.isEmpty()) {
            return new SearchResult(terms, MatchMode.NONE, 0, List.of());
        }

        List<PostingList> lists = new ArrayList<>();
        List<FieldPostingList> titleLists = new ArrayList<>();   // same order as lists; an entry may be null
        boolean allTermsKnown = true;
        for (String term : terms) {
            PostingList list = index.postings(term);
            if (list == null) {
                allTermsKnown = false;   // AND cannot match anything; go straight to OR
            } else {
                lists.add(list);
                titleLists.add(index.titlePostings(term));
            }
        }

        if (allTermsKnown) {
            SearchResult and = searchAnd(terms, lists, titleLists, k);
            if (and.totalMatches() > 0) {
                return and;
            }
        }
        return searchOr(terms, lists, titleLists, k);
    }

    /** Document-at-a-time: score only documents in the intersection of all lists. */
    private SearchResult searchAnd(List<String> terms, List<PostingList> lists, List<FieldPostingList> titleLists,
                                   int k) {
        List<int[]> docIdLists = new ArrayList<>(lists.size());
        for (PostingList list : lists) {
            docIdLists.add(list.docIds());
        }
        int[] candidates = PostingIntersection.intersectAll(docIdLists);
        if (candidates.length == 0) {
            return new SearchResult(terms, MatchMode.AND, 0, List.of());
        }

        double[] idf = idfs(lists);
        double[] scores = new double[candidates.length];
        for (int t = 0; t < lists.size(); t++) {
            PostingList list = lists.get(t);
            TitleCursor title = new TitleCursor(titleLists.get(t));
            int cursor = 0;   // moving pointer into this term's posting list
            for (int c = 0; c < candidates.length; c++) {
                int doc = candidates[c];
                // both arrays are sorted, so the pointer only ever moves forward
                while (list.docId(cursor) < doc) {
                    cursor++;
                }
                scores[c] += termScore(idf[t], doc, list.freq(cursor), title.freqAt(doc));
            }
        }

        TopKHeap heap = new TopKHeap(k);
        for (int c = 0; c < candidates.length; c++) {
            heap.offer(candidates[c], scores[c]);
        }
        return new SearchResult(terms, MatchMode.AND, candidates.length, toHits(heap));
    }

    /** Term-at-a-time: add each term's score into an accumulator array indexed by doc id. */
    private SearchResult searchOr(List<String> terms, List<PostingList> lists, List<FieldPostingList> titleLists,
                                  int k) {
        double[] accumulator = new double[docs.size()];
        int totalPostings = 0;
        for (PostingList list : lists) {
            totalPostings += list.size();
        }
        int[] touched = new int[Math.min(docs.size(), totalPostings)];
        int touchedCount = 0;

        double[] idf = idfs(lists);
        for (int t = 0; t < lists.size(); t++) {
            PostingList list = lists.get(t);
            TitleCursor title = new TitleCursor(titleLists.get(t));
            for (int i = 0; i < list.size(); i++) {
                int doc = list.docId(i);
                if (accumulator[doc] == 0) {
                    touched[touchedCount++] = doc;   // every term score is > 0, so 0 means "first time"
                }
                accumulator[doc] += termScore(idf[t], doc, list.freq(i), title.freqAt(doc));
            }
        }

        TopKHeap heap = new TopKHeap(k);
        for (int i = 0; i < touchedCount; i++) {
            heap.offer(touched[i], accumulator[touched[i]]);
        }
        return new SearchResult(terms, MatchMode.OR, touchedCount, toHits(heap));
    }

    /**
     * Score of one term in one document. {@code tfTotal} counts every field; {@code tfTitle} only the
     * title field (title + aliases), so the body count is {@code tfTotal − tfTitle}.
     */
    private double termScore(double idf, int doc, int tfTotal, int tfTitle) {
        return switch (ranking.model()) {
            case BM25 -> bm25.termScore(idf, tfTotal, docs.length(doc));
            case BM25F -> bm25f.termScore(idf, tfTitle, docs.titleFieldLength(doc),
                    tfTotal - tfTitle, docs.bodyLength(doc));
            case BM25_SUM -> sumTermScore(idf, doc, tfTotal - tfTitle, tfTitle);
        };
    }

    private double sumTermScore(double idf, int doc, int tfBody, int tfTitle) {
        double score = 0;
        if (tfBody > 0) {
            score += bodyBm25.termScore(idf, tfBody, docs.bodyLength(doc));
        }
        if (tfTitle > 0) {
            score += ranking.wTitle() * titleBm25.termScore(idf, tfTitle, docs.titleFieldLength(doc));
        }
        return score;
    }

    private double[] idfs(List<PostingList> lists) {
        double[] idf = new double[lists.size()];
        for (int t = 0; t < lists.size(); t++) {
            // document frequency counts documents with the term in ANY field (one IDF for all fields)
            idf[t] = Bm25.idf(docs.size(), lists.get(t).size());
        }
        return idf;
    }

    private List<Hit> toHits(TopKHeap heap) {
        List<Hit> hits = new ArrayList<>(heap.size());
        for (ScoredDoc sd : heap.toSortedList()) {
            hits.add(new Hit(sd.docId(), docs.wikiId(sd.docId()), docs.title(sd.docId()), sd.score()));
        }
        return hits;
    }

    /**
     * Walks one term's title-field list alongside the main list. Docs are asked for in increasing order,
     * so the pointer only moves forward: O(length of the title list) in total.
     */
    private static final class TitleCursor {
        private final FieldPostingList list;   // null: the term is in no title field
        private int i;

        TitleCursor(FieldPostingList list) {
            this.list = list;
        }

        /** How many times the term is in this document's title field (0 if not). */
        int freqAt(int doc) {
            if (list == null) {
                return 0;
            }
            while (i < list.size() && list.docId(i) < doc) {
                i++;
            }
            return i < list.size() && list.docId(i) == doc ? list.freq(i) : 0;
        }
    }
}
