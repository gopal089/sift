package dev.sift.engine.spell;

import dev.sift.engine.index.Vocabulary;
import dev.sift.engine.text.StopWords;
import dev.sift.engine.text.Tokenizer;
import java.util.ArrayList;
import java.util.List;

/**
 * "Did you mean …?" A query word that is not in the vocabulary (the real words found in at least
 * {@link Vocabulary#MIN_DOCUMENTS} articles) is probably misspelled. We look for vocabulary words close to it:
 *
 * <ol>
 *   <li>The BK-tree returns every word within Levenshtein distance 2 (1 for words of up to 4 letters,
 *       where 2 edits would change half the word).</li>
 *   <li>Among those, the best is the closest one, where a swap of two neighbouring letters counts as one
 *       edit; ties go to the word found in the most articles, then alphabetically.</li>
 * </ol>
 */
public final class SpellChecker {

    static final int MAX_DISTANCE = 2;
    static final int SHORT_WORD = 4;

    private final Vocabulary vocabulary;
    private final BkTree tree = new BkTree();
    private final int[] vocabularyIndex;   // tree word index → index in the vocabulary

    public SpellChecker(Vocabulary vocabulary) {
        this.vocabulary = vocabulary;
        // Insert in a fixed shuffled order. Sorted order would hang long chains of similar words
        // ("abandon", "abandoned", ...) under each other and make the tree deep and slow.
        int n = vocabulary.size();
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Random random = new java.util.Random(42);   // fixed seed: the same tree on every start
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }
        this.vocabularyIndex = new int[n];
        for (int i : order) {
            vocabularyIndex[tree.add(vocabulary.word(i))] = i;
        }
    }

    /** The best correction for one lowercase word, or null if the word is known or nothing is close enough. */
    public String suggest(String word) {
        if (!Vocabulary.isPlainWord(word) || vocabulary.contains(word)) {
            return null;
        }
        int maxDistance = word.length() <= SHORT_WORD ? 1 : MAX_DISTANCE;
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        int bestCount = -1;
        for (BkTree.Match match : tree.search(word, maxDistance)) {
            String candidate = tree.word(match.wordIndex());
            int distance = EditDistance.withSwaps(word, candidate);
            int count = vocabulary.documentCount(vocabularyIndex[match.wordIndex()]);
            boolean better = distance < bestDistance
                    || (distance == bestDistance && count > bestCount)
                    || (distance == bestDistance && count == bestCount && candidate.compareTo(best) < 0);
            if (better) {
                best = candidate;
                bestDistance = distance;
                bestCount = count;
            }
        }
        return best;
    }

    /**
     * The whole query with its unknown words corrected ("histroy of india" → "history of india"),
     * or null if no word needed a correction. Stop words and question words are left alone.
     */
    public String correctQuery(String query) {
        List<String> words = new ArrayList<>();
        Tokenizer.forEachWord(query, (word, start, end) -> words.add(word));
        boolean changed = false;
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            if (StopWords.isStopWord(word, true)) {
                continue;
            }
            String suggestion = suggest(word);
            if (suggestion != null) {
                words.set(i, suggestion);
                changed = true;
            }
        }
        return changed ? String.join(" ", words) : null;
    }

    /** Edit distances computed by the last lookup (to show how much of the tree was skipped). */
    public int lastSearchDistanceCalls() {
        return tree.lastSearchDistanceCalls();
    }

    public int wordCount() {
        return tree.size();
    }
}
