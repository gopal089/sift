package dev.sift.engine.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sift.engine.index.IndexBuilder;
import dev.sift.engine.index.InvertedIndex;
import dev.sift.engine.search.SearchResult.Hit;
import dev.sift.engine.search.SearchResult.MatchMode;
import dev.sift.engine.vector.Float32Vectors;
import dev.sift.engine.vector.Hnsw;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmartSearcherTest {

    @Test
    void rrfRewardsBeingGoodInBothLists() {
        // keyword: Plant(10), Leaf(11), Photosynthesis(12). meaning: Photosynthesis(12), Sugar(13).
        List<ScoredDoc> keyword = List.of(new ScoredDoc(10, 30), new ScoredDoc(11, 20), new ScoredDoc(12, 10));
        List<ScoredDoc> meaning = List.of(new ScoredDoc(12, 0.8), new ScoredDoc(13, 0.7));
        List<ScoredDoc> fused = SmartSearcher.fuse(List.of(keyword, meaning), 60);
        // 12: 1/63 + 1/61 = 0.0323 | 10: 1/61 | 11 and 13: 1/62 each → tie, smaller id first
        assertEquals(List.of(12, 10, 11, 13), fused.stream().map(ScoredDoc::docId).toList());
        assertEquals(1.0 / 63 + 1.0 / 61, fused.get(0).score(), 1e-12);
        assertEquals(fused.get(2).score(), fused.get(3).score(), 0);
    }

    private static SmartSearcher toy(SmartSearcher.Mode mode) {
        IndexBuilder b = new IndexBuilder();
        b.addDocument(100, "Photosynthesis", "Green leaves turn light into sugar.");
        b.addDocument(101, "Plant", "A plant grows in soil and needs water.");
        b.addDocument(102, "Cooking", "People and plants make food in different ways.");
        b.addDocument(103, "Car", "A car has four wheels.");
        InvertedIndex index = b.build();
        // 2-d "meaning" vectors: Photosynthesis is nearest to the query direction, Car is farthest
        Float32Vectors vectors = Float32Vectors.of(new float[] {1, 0, 0.8f, 0.6f, 0.6f, 0.8f, 0, 1}, 2);
        Hnsw graph = Hnsw.build(vectors, 2, 4, 1, null);
        float n = (float) Math.hypot(0.98, 0.2);
        return new SmartSearcher(new Searcher(index, Ranking.DEFAULT), text -> new float[] {0.98f / n, 0.2f / n},
                graph, index.docs(), mode, 50, "float32");
    }

    @Test
    void semanticFindsTheArticleThatSharesNoWordWithTheQuery() {
        SearchResult r = toy(SmartSearcher.Mode.SEMANTIC).search("how do plants make food", 10);
        assertEquals(MatchMode.SEMANTIC, r.mode());
        assertEquals(List.of("Photosynthesis", "Plant", "Cooking", "Car"), r.hits().stream().map(Hit::title).toList());
        // "how" is a question word (dropped); "do" is not a stop word, so it stays. Kept for the snippets.
        assertEquals(List.of("do", "plant", "make", "food"), r.terms());
    }

    @Test
    void hybridMixesKeywordAndMeaningRanks() {
        SmartSearcher.Timed t = toy(SmartSearcher.Mode.HYBRID).searchTimed("how do plants make food", 10);
        // keyword: no article has all of do + plant + make + food, so OR: Cooking (3 terms) #1, Plant #2.
        // Cooking: 1/61 + 1/63 (meaning #3) = 0.032266 | Plant: 1/62 + 1/62 = 0.032258 | Photosynthesis: 1/61 only
        assertEquals(MatchMode.HYBRID, t.result().mode());
        assertEquals(List.of("Cooking", "Plant", "Photosynthesis", "Car"),
                t.result().hits().stream().map(Hit::title).toList());
        assertEquals(1.0 / 61 + 1.0 / 63, t.result().hits().get(0).score(), 1e-12);
        assertTrue(t.embedMs() >= 0 && t.vectorMs() >= 0 && t.keywordMs() >= 0);
    }

    @Test
    void aQueryWithoutLettersOrDigitsGivesNothing() {
        SearchResult r = toy(SmartSearcher.Mode.HYBRID).search("?!", 10);
        assertEquals(MatchMode.NONE, r.mode());
        assertTrue(r.hits().isEmpty());
    }

    @Test
    void kLimitsTheList() {
        assertEquals(2, toy(SmartSearcher.Mode.HYBRID).search("plants", 2).hits().size());
    }
}
