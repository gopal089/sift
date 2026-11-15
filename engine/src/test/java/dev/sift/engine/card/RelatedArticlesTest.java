package dev.sift.engine.card;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sift.engine.search.ScoredDoc;
import dev.sift.engine.vector.ExactIndex;
import dev.sift.engine.vector.Float32Vectors;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RelatedArticlesTest {

    // 5 unit vectors on a circle: doc 0 at 0°, doc 1 at ~37°, doc 2 at ~53°, doc 3 at 90°, doc 4 at 180°
    private static final Float32Vectors VECTORS = Float32Vectors.of(new float[] {
        1, 0,   0.8f, 0.6f,   0.6f, 0.8f,   0, 1,   -1, 0}, 2);
    private final RelatedArticles related = new RelatedArticles(VECTORS, new ExactIndex("test", VECTORS));

    private static List<Integer> ids(List<ScoredDoc> docs) {
        return docs.stream().map(ScoredDoc::docId).toList();
    }

    @Test
    void nearestFirstAndNeverTheArticleItself() {
        assertEquals(List.of(1, 2, 3), ids(related.of(0, Set.of(), 3)));
    }

    @Test
    void articlesAlreadyOnThePageAreSkipped() {
        assertEquals(List.of(2, 3, 4), ids(related.of(0, Set.of(1), 5)));
    }

    @Test
    void stillFillsTheListWhenManyAreExcluded() {
        assertEquals(List.of(4), ids(related.of(0, Set.of(1, 2, 3), 5)));
    }
}
