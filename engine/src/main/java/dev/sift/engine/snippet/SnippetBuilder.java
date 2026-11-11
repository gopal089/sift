package dev.sift.engine.snippet;

import dev.sift.engine.index.DocTable;
import dev.sift.engine.index.PostingList;
import dev.sift.engine.index.SearchIndex;
import dev.sift.engine.snippet.Snippet.Highlight;
import dev.sift.engine.text.Tokenizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks the most useful part of an article for a query and marks the query words in it.
 *
 * <ol>
 *   <li>From the index, take the <b>positions</b> (word numbers) of every query term in this document's body.</li>
 *   <li>Slide a window of {@code windowWords} words over those positions and keep the window that contains
 *       the most <i>different</i> query terms; ties: more matches, then the earlier window.</li>
 *   <li>Walk the article text word by word (with the same tokenizer the index used) up to the end of the
 *       window, to learn where those words are as characters. Cut the text there and record the highlights.</li>
 * </ol>
 * If no query term is in the body (e.g. only the title matched), the snippet is the start of the article.
 */
public final class SnippetBuilder {

    /** Words shown before the first matching word, so the match is not the very first thing. */
    static final int LEAD_WORDS = 5;

    private SnippetBuilder() {
    }

    public static Snippet build(SearchIndex index, int docId, List<String> terms, String text, int windowWords) {
        DocTable docs = index.docs();
        int bodyStart = docs.titleLength(docId);          // positions are laid out [title][body][alias]
        int bodyLength = docs.bodyLength(docId);

        // 1. (body word number, which query term) for every match in the body
        List<int[]> matches = new ArrayList<>();
        for (int t = 0; t < terms.size(); t++) {
            PostingList list = index.postings(terms.get(t));
            if (list == null) {
                continue;
            }
            int i = Arrays.binarySearch(list.docIds(), docId);
            if (i < 0) {
                continue;   // this document does not contain the term
            }
            for (int position : list.positions(i)) {
                int word = position - bodyStart;
                if (word >= 0 && word < bodyLength) {
                    matches.add(new int[] {word, t});
                }
            }
        }
        matches.sort((a, b) -> Integer.compare(a[0], b[0]));

        // 2. choose the window
        int[] best = bestWindow(matches, terms.size(), windowWords);   // first and last matching word in it
        // Show a few words before the first match, but never so many that the last match falls out.
        int spare = windowWords - (best[1] - best[0] + 1);
        int firstWord = Math.max(0, best[0] - Math.min(LEAD_WORDS, spare));
        int lastWord = firstWord + windowWords;   // exclusive

        // 3. find the characters of words firstWord .. lastWord-1 and which of them are query words
        Set<String> wanted = new HashSet<>(terms);
        int[] cut = {-1, -1};                     // character range of the window in the article text
        boolean[] more = {false};                 // is there text after the window?
        List<Highlight> inText = new ArrayList<>();
        int[] wordNumber = {0};
        Tokenizer.forEachWord(text, (word, start, end) -> {
            int n = wordNumber[0]++;
            if (n >= lastWord) {
                more[0] = true;
                return false;                     // past the window: stop reading
            }
            if (n >= firstWord) {
                if (cut[0] < 0) {
                    cut[0] = start;
                }
                cut[1] = end;
                if (wanted.contains(Tokenizer.stemWord(word))) {
                    inText.add(new Highlight(start, end));
                }
            }
            return true;
        });
        if (cut[0] < 0) {
            return new Snippet("", List.of());    // the article has no words
        }

        boolean cutAtStart = firstWord > 0;
        int from = cutAtStart ? cut[0] : 0;                    // keep leading punctuation at the very start
        int to = more[0] ? cut[1] : text.length();             // keep the final full stop at the very end
        String prefix = cutAtStart ? "… " : "";
        String suffix = more[0] ? " …" : "";
        List<Highlight> highlights = new ArrayList<>(inText.size());
        for (Highlight h : inText) {                           // move ranges from article offsets to snippet offsets
            highlights.add(new Highlight(h.start() - from + prefix.length(), h.end() - from + prefix.length()));
        }
        return new Snippet(prefix + text.substring(from, to) + suffix, highlights);
    }

    /**
     * The body word numbers of the first and last match in the best window, or {0, 0} if there are no matches.
     * {@code matches} are (word number, term index) pairs sorted by word number. Two pointers: for each
     * left match, the right pointer only ever moves forward, so this is O(number of matches).
     */
    static int[] bestWindow(List<int[]> matches, int termCount, int windowWords) {
        int[] perTerm = new int[termCount];   // how many matches of each term are inside the window
        int distinct = 0;
        int bestDistinct = 0;
        int bestCount = 0;
        int bestStart = 0;
        int bestEnd = 0;
        int right = 0;
        for (int left = 0; left < matches.size(); left++) {
            int windowEnd = matches.get(left)[0] + windowWords;
            while (right < matches.size() && matches.get(right)[0] < windowEnd) {
                if (perTerm[matches.get(right)[1]]++ == 0) {
                    distinct++;
                }
                right++;
            }
            int count = right - left;
            if (distinct > bestDistinct || (distinct == bestDistinct && count > bestCount)) {
                bestDistinct = distinct;
                bestCount = count;
                bestStart = matches.get(left)[0];
                bestEnd = matches.get(right - 1)[0];
            }
            if (--perTerm[matches.get(left)[1]] == 0) {   // the left match leaves the window
                distinct--;
            }
        }
        return new int[] {bestStart, bestEnd};
    }
}
