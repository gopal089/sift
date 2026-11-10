package dev.sift.engine.search;

import dev.sift.engine.index.DocTable;
import dev.sift.engine.index.InvertedIndex;
import dev.sift.engine.index.PostingList;
import dev.sift.engine.search.SearchResult.Hit;
import dev.sift.engine.search.SearchResult.MatchMode;
import dev.sift.engine.text.Tokenizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Keyword search: tokenize the query, find matching documents, score them with BM25, keep the top K.
 *
 * <ol>
 *   <li><b>AND</b> first: only documents that contain every query term.</li>
 *   <li><b>OR</b> fallback if AND finds nothing (or a term is not in the index): any term may match.</li>
 * </ol>
 */
public final class Searcher {

    private final InvertedIndex index;
    private final DocTable docs;
    private final Bm25 bm25;

    public Searcher(InvertedIndex index) {
        this.index = index;
        this.docs = index.docs();
        this.bm25 = new Bm25(docs.size(), docs.averageLength());
    }

    /** One line describing the ranking settings, written into every eval results file. */
    public String description() {
        return bm25 + ", title indexed as the first words of the body";
    }

    public SearchResult search(String query, int k) {
        List<String> terms = Tokenizer.queryTerms(query);
        if (terms.isEmpty()) {
            return new SearchResult(terms, MatchMode.NONE, 0, List.of());
        }

        List<PostingList> lists = new ArrayList<>();
        boolean allTermsKnown = true;
        for (String term : terms) {
            PostingList list = index.postings(term);
            if (list == null) {
                allTermsKnown = false;   // AND cannot match anything; go straight to OR
            } else {
                lists.add(list);
            }
        }

        if (allTermsKnown) {
            SearchResult and = searchAnd(terms, lists, k);
            if (and.totalMatches() > 0) {
                return and;
            }
        }
        return searchOr(terms, lists, k);
    }

    /** Document-at-a-time: score only documents in the intersection of all lists. */
    private SearchResult searchAnd(List<String> terms, List<PostingList> lists, int k) {
        List<int[]> docIdLists = new ArrayList<>(lists.size());
        for (PostingList list : lists) {
            docIdLists.add(list.docIds());
        }
        int[] candidates = PostingIntersection.intersectAll(docIdLists);
        if (candidates.length == 0) {
            return new SearchResult(terms, MatchMode.AND, 0, List.of());
        }

        double[] idf = idfs(lists);
        int[] cursor = new int[lists.size()];   // one moving pointer per posting list
        double[] scores = new double[candidates.length];
        for (int t = 0; t < lists.size(); t++) {
            PostingList list = lists.get(t);
            for (int c = 0; c < candidates.length; c++) {
                int doc = candidates[c];
                // both arrays are sorted, so the pointer only ever moves forward
                while (list.docId(cursor[t]) < doc) {
                    cursor[t]++;
                }
                scores[c] += bm25.termScore(idf[t], list.freq(cursor[t]), docs.length(doc));
            }
        }

        TopKHeap heap = new TopKHeap(k);
        for (int c = 0; c < candidates.length; c++) {
            heap.offer(candidates[c], scores[c]);
        }
        return new SearchResult(terms, MatchMode.AND, candidates.length, toHits(heap));
    }

    /** Term-at-a-time: add each term's score into an accumulator array indexed by doc id. */
    private SearchResult searchOr(List<String> terms, List<PostingList> lists, int k) {
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
            for (int i = 0; i < list.size(); i++) {
                int doc = list.docId(i);
                if (accumulator[doc] == 0) {
                    touched[touchedCount++] = doc;   // every BM25 term score is > 0, so 0 means "first time"
                }
                accumulator[doc] += bm25.termScore(idf[t], list.freq(i), docs.length(doc));
            }
        }

        TopKHeap heap = new TopKHeap(k);
        for (int i = 0; i < touchedCount; i++) {
            heap.offer(touched[i], accumulator[touched[i]]);
        }
        return new SearchResult(terms, MatchMode.OR, touchedCount, toHits(heap));
    }

    private double[] idfs(List<PostingList> lists) {
        double[] idf = new double[lists.size()];
        for (int t = 0; t < lists.size(); t++) {
            idf[t] = bm25.idf(lists.get(t).size());
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
}
