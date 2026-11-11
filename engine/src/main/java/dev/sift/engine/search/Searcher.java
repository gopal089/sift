package dev.sift.engine.search;

import dev.sift.engine.index.DocTable;
import dev.sift.engine.index.FieldPostingList;
import dev.sift.engine.index.SearchIndex;
import dev.sift.engine.index.PostingList;
import dev.sift.engine.search.SearchResult.Hit;
import dev.sift.engine.search.SearchResult.MatchMode;
import dev.sift.engine.text.Tokenizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Keyword search: tokenize the query, find matching documents, score them, keep the top K.
 *
 * <p>Two ways to match, chosen by {@link Ranking#softAnd()}:
 * <ul>
 *   <li><b>Strict</b>: AND first (documents containing every query term); OR only if AND finds nothing.</li>
 *   <li><b>Soft AND</b>: any term may match, but documents containing more of the query terms always rank
 *       above documents containing fewer. Within the same number of matched terms, the score decides.</li>
 * </ul>
 * Scoring follows the {@link Ranking} model: BM25, BM25F, or BM25(body) + w × BM25(title), plus an
 * optional bonus when a term is one of the document's alias (redirect) words.
 */
public final class Searcher {

    /**
     * Soft AND sorts by (matched terms, score). We pack both into one number: matched × TIER + score.
     * Scores are far below TIER, so a document with more matched terms always has the bigger number.
     */
    static final double TIER = 1_000_000.0;

    private final SearchIndex index;
    private final DocTable docs;
    private final Ranking ranking;
    private final Bm25 bm25;          // single field: everything together
    private final Bm25F bm25f;        // title + body, combined then saturated
    private final Bm25 bodyBm25;      // the "sum" model: body ...
    private final Bm25 titleBm25;     // ... and title, each saturated separately

    public Searcher(SearchIndex index) {
        this(index, Ranking.DEFAULT);
    }

    public Searcher(SearchIndex index, Ranking ranking) {
        this.index = index;
        this.docs = index.docs();
        this.ranking = ranking;
        this.bm25 = new Bm25(docs.size(), docs.averageLength(), ranking.k1(), ranking.bBody());
        this.bm25f = new Bm25F(ranking.k1(), ranking.wTitle(), ranking.bTitle(), ranking.bBody(),
                docs.averageTitleLength(), docs.averageBodyLength());
        this.bodyBm25 = new Bm25(docs.size(), docs.averageBodyLength(), ranking.k1(), ranking.bBody());
        this.titleBm25 = new Bm25(docs.size(), docs.averageTitleLength(), ranking.k1(), ranking.bTitle());
    }

    /** One line describing the search settings, written into every eval results file. */
    public String description() {
        return ranking.toString();
    }

    public SearchResult search(String query, int k) {
        List<String> terms = Tokenizer.queryTerms(query, ranking.dropQuestionWords());
        if (terms.isEmpty()) {
            return new SearchResult(terms, MatchMode.NONE, 0, List.of());
        }

        // Look up each term's three posting lists. Terms that are in no document are skipped.
        List<PostingList> lists = new ArrayList<>();
        List<FieldPostingList> titleLists = new ArrayList<>();   // same order as lists; an entry may be null
        List<FieldPostingList> aliasLists = new ArrayList<>();
        for (String term : terms) {
            PostingList list = index.postings(term);
            if (list != null) {
                lists.add(list);
                titleLists.add(index.titlePostings(term));
                aliasLists.add(index.aliasPostings(term));
            }
        }
        boolean allTermsKnown = lists.size() == terms.size();

        if (ranking.softAnd()) {
            return searchAny(terms, lists, titleLists, aliasLists, k, true);
        }
        if (allTermsKnown) {   // if a term is unknown, AND cannot match anything: go straight to OR
            SearchResult and = searchAnd(terms, lists, titleLists, aliasLists, k);
            if (and.totalMatches() > 0) {
                return and;
            }
        }
        return searchAny(terms, lists, titleLists, aliasLists, k, false);
    }

    /** Document-at-a-time: score only documents in the intersection of all lists. */
    private SearchResult searchAnd(List<String> terms, List<PostingList> lists, List<FieldPostingList> titleLists,
                                   List<FieldPostingList> aliasLists, int k) {
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
            FieldCursor title = new FieldCursor(titleLists.get(t));
            FieldCursor alias = new FieldCursor(aliasLists.get(t));
            int cursor = 0;   // moving pointer into this term's posting list
            for (int c = 0; c < candidates.length; c++) {
                int doc = candidates[c];
                // both arrays are sorted, so the pointer only ever moves forward
                while (list.docId(cursor) < doc) {
                    cursor++;
                }
                scores[c] += termScore(idf[t], doc, list.freq(cursor), title.freqAt(doc), alias.freqAt(doc));
            }
        }

        TopKHeap heap = new TopKHeap(k);
        for (int c = 0; c < candidates.length; c++) {
            heap.offer(candidates[c], withPageRank(candidates[c], scores[c]));
        }
        return new SearchResult(terms, MatchMode.AND, candidates.length, toHits(heap, false));
    }

    /**
     * Term-at-a-time over every document that contains at least one term: add each term's score into an
     * accumulator array indexed by doc id, and count the matched terms per document. With {@code soft},
     * rank by (matched terms, score); without it, this is the plain OR fallback (score only).
     */
    private SearchResult searchAny(List<String> terms, List<PostingList> lists, List<FieldPostingList> titleLists,
                                   List<FieldPostingList> aliasLists, int k, boolean soft) {
        double[] accumulator = new double[docs.size()];
        int[] matched = new int[docs.size()];   // how many query terms each document contains
        long totalPostings = 0;
        for (PostingList list : lists) {
            totalPostings += list.size();
        }
        int[] touched = new int[(int) Math.min(docs.size(), totalPostings)];
        int touchedCount = 0;

        double[] idf = idfs(lists);
        for (int t = 0; t < lists.size(); t++) {
            PostingList list = lists.get(t);
            FieldCursor title = new FieldCursor(titleLists.get(t));
            FieldCursor alias = new FieldCursor(aliasLists.get(t));
            for (int i = 0; i < list.size(); i++) {
                int doc = list.docId(i);
                if (matched[doc]++ == 0) {
                    touched[touchedCount++] = doc;   // first time we see this document
                }
                accumulator[doc] += termScore(idf[t], doc, list.freq(i), title.freqAt(doc), alias.freqAt(doc));
            }
        }

        TopKHeap heap = new TopKHeap(k);
        for (int i = 0; i < touchedCount; i++) {
            int doc = touched[i];
            double score = withPageRank(doc, accumulator[doc]);
            heap.offer(doc, soft ? matched[doc] * TIER + score : score);
        }
        return new SearchResult(terms, soft ? MatchMode.SOFT_AND : MatchMode.OR, touchedCount, toHits(heap, soft));
    }

    /**
     * Score of one term in one document. {@code tfTotal} counts every field, so the body count is
     * {@code tfTotal − tfTitle − tfAlias}.
     */
    private double termScore(double idf, int doc, int tfTotal, int tfTitle, int tfAlias) {
        int tfBody = tfTotal - tfTitle - tfAlias;
        double score = switch (ranking.model()) {
            case BM25 -> bm25.termScore(idf, tfTotal, docs.length(doc));
            case BM25F -> bm25f.termScore(idf, tfTitle, docs.titleLength(doc), tfBody, docs.bodyLength(doc));
            case BM25_SUM -> (tfBody > 0 ? bodyBm25.termScore(idf, tfBody, docs.bodyLength(doc)) : 0)
                    + (tfTitle > 0 ? ranking.wTitle() * titleBm25.termScore(idf, tfTitle, docs.titleLength(doc)) : 0);
        };
        if (tfAlias > 0) {
            // BM25 of a field with tf = 1 and no length normalisation (b = 0) is exactly idf
            score += ranking.wAlias() * idf;
        }
        return score;
    }

    /**
     * Lifts important pages a little: score × (1 + alpha × boost), where boost is the document's PageRank
     * on a 0..1 scale. With alpha = 0.2 the most important page gets +20%, the least important +0%.
     */
    private double withPageRank(int doc, double score) {
        return ranking.alpha() == 0 ? score : score * (1.0 + ranking.alpha() * docs.pageRankBoost(doc));
    }

    private double[] idfs(List<PostingList> lists) {
        double[] idf = new double[lists.size()];
        for (int t = 0; t < lists.size(); t++) {
            // document frequency counts documents with the term in ANY field (one IDF for all fields)
            idf[t] = Bm25.idf(docs.size(), lists.get(t).size());
        }
        return idf;
    }

    private List<Hit> toHits(TopKHeap heap, boolean soft) {
        List<Hit> hits = new ArrayList<>(heap.size());
        for (ScoredDoc sd : heap.toSortedList()) {
            // soft AND: remove the "matched terms" tier again so the user sees the plain score
            double score = soft ? sd.score() % TIER : sd.score();
            hits.add(new Hit(sd.docId(), docs.wikiId(sd.docId()), docs.title(sd.docId()), score));
        }
        return hits;
    }

    /**
     * Walks one term's title (or alias) list alongside the main list. Docs are asked for in increasing
     * order, so the pointer only moves forward: O(length of the field list) in total.
     */
    private static final class FieldCursor {
        private final FieldPostingList list;   // null: the term is not in this field anywhere
        private int i;

        FieldCursor(FieldPostingList list) {
            this.list = list;
        }

        /** How many times the term is in this document's field (0 if not). */
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
