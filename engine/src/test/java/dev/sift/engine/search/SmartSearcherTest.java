package dev.sift.engine.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sift.engine.index.IndexBuilder;
import dev.sift.engine.index.InvertedIndex;
import dev.sift.engine.search.SearchResult.Hit;
import dev.sift.engine.search.SearchResult.MatchMode;
import dev.sift.engine.vector.Float32Vectors;
import dev.sift.engine.vector.ExactIndex;
import dev.sift.engine.vector.Hnsw;
import dev.sift.engine.vector.HnswIndex;
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
        return toy(mode, 1);
    }

    private static SmartSearcher toy(SmartSearcher.Mode mode, double keywordWeight) {
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
                new HnswIndex("toy", graph, 50, 4), index.docs(), mode, keywordWeight);
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
    void keywordWeightTwoLetsTheKeywordListLead() {
        // weight 2: Cooking 2/61 + 1/63 = 0.0487, Plant 2/62 + 1/62 = 0.0484, Photosynthesis 1/61 = 0.0164.
        // The gap between keyword hits and meaning-only hits grows; the order stays the same here.
        SearchResult r = toy(SmartSearcher.Mode.HYBRID, 2).search("how do plants make food", 10);
        assertEquals(List.of("Cooking", "Plant", "Photosynthesis", "Car"), r.hits().stream().map(Hit::title).toList());
        assertEquals(2.0 / 61 + 1.0 / 63, r.hits().get(0).score(), 1e-12);
        assertEquals(3.0 / 62, r.hits().get(1).score(), 1e-12);
    }

    @Test
    void weightedRrfExample() {
        // keyword [10, 11], meaning [11, 12]; keyword weight 2:
        // 10: 2/61 = 0.0328 | 11: 2/62 + 1/61 = 0.0487 | 12: 1/62 = 0.0161
        List<ScoredDoc> fused = SmartSearcher.fuse(List.of(List.of(new ScoredDoc(10, 9), new ScoredDoc(11, 8)),
                List.of(new ScoredDoc(11, 0.9), new ScoredDoc(12, 0.8))), new double[] {2, 1}, 60);
        assertEquals(List.of(11, 10, 12), fused.stream().map(ScoredDoc::docId).toList());
        assertEquals(2.0 / 62 + 1.0 / 61, fused.get(0).score(), 1e-12);
    }

    @Test
    void exactIndexWithTwoVectorsUsesTheBetterOne() {
        // doc 0: (1,0) and (0,1); doc 1: (0.8,0.6) twice. Query (0,1): doc 0's second vector matches exactly.
        Float32Vectors first = Float32Vectors.of(new float[] {1, 0, 0.8f, 0.6f}, 2);
        Float32Vectors second = Float32Vectors.of(new float[] {0, 1, 0.8f, 0.6f}, 2);
        List<ScoredDoc> top = new ExactIndex("toy", first, second).top(new float[] {0, 1}, 2);
        assertEquals(List.of(0, 1), top.stream().map(ScoredDoc::docId).toList());
        assertEquals(1.0, top.get(0).score(), 1e-6);
        assertEquals(List.of(1, 0), new ExactIndex("toy", first).top(new float[] {0, 1}, 2).stream()
                .map(ScoredDoc::docId).toList());
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
