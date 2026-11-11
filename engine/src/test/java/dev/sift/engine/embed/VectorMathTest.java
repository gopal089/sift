package dev.sift.engine.embed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VectorMathTest {

    @Test
    void meanPoolingIgnoresPaddingThenNormalises() {
        float[][] tokens = {{1, 0}, {3, 4}, {100, 100}};   // the last row is padding
        // mean of (1,0) and (3,4) = (2,2); length 2√2; normalised = (1/√2, 1/√2)
        float[] v = VectorMath.meanPoolNormalise(tokens, new long[] {1, 1, 0});
        float h = (float) (1 / Math.sqrt(2));
        assertArrayEquals(new float[] {h, h}, v, 1e-6f);
    }

    @Test
    void clsPoolingTakesTheFirstVectorOnly() {
        float[][] tokens = {{3, 4}, {100, 0}};
        assertArrayEquals(new float[] {0.6f, 0.8f}, VectorMath.clsNormalise(tokens), 1e-6f);   // (3,4) / 5
    }

    @Test
    void dotAndCosine() {
        float[] a = {1, 2, 3, 4, 5};
        float[] b = {5, 4, 3, 2, 1};
        assertEquals(35f, VectorMath.dot(a, b), 1e-6f);   // 5+8+9+8+5 (odd length: tests the tail loop)
        assertEquals(1.0, VectorMath.cosine(a, new float[] {2, 4, 6, 8, 10}), 1e-12);
        assertEquals(0.0, VectorMath.cosine(new float[] {1, 0}, new float[] {0, 3}), 1e-12);
        assertEquals(-1.0, VectorMath.cosine(new float[] {1, 1}, new float[] {-2, -2}), 1e-12);
    }
}
