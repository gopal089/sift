package dev.sift.engine.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class VarIntTest {

    private static byte[] encode(int... values) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int v : values) {
            VarInt.write(out, v);
        }
        return out.toByteArray();
    }

    @Test
    void knownEncodings() throws IOException {
        assertArrayEquals(new byte[] {0x00}, encode(0));
        assertArrayEquals(new byte[] {0x05}, encode(5));
        assertArrayEquals(new byte[] {0x7F}, encode(127));
        assertArrayEquals(new byte[] {(byte) 0x80, 0x01}, encode(128));
        // 300 = 10 0101100 → low 7 bits 0101100 with the "more" bit = AC, then 10 = 02
        assertArrayEquals(new byte[] {(byte) 0xAC, 0x02}, encode(300));
    }

    @Test
    void roundTripAtEveryByteLengthBoundary() throws IOException {
        int[] values = {0, 1, 127, 128, 16_383, 16_384, 2_097_151, 2_097_152, 268_435_455, 268_435_456,
                Integer.MAX_VALUE};
        int[] expectedSizes = {1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5};
        ByteBuffer in = ByteBuffer.wrap(encode(values));
        for (int i = 0; i < values.length; i++) {
            assertEquals(expectedSizes[i], VarInt.size(values[i]), "size of " + values[i]);
            assertEquals(values[i], VarInt.read(in));
        }
        assertEquals(0, in.remaining());
    }

    @Test
    void writeReturnsTheNumberOfBytes() throws IOException {
        assertEquals(2, VarInt.write(new ByteArrayOutputStream(), 300));
    }

    @Test
    void negativeNumbersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> VarInt.write(new ByteArrayOutputStream(), -1));
    }

    @Test
    void corruptInputWithEndlessContinuationBitsFails() {
        ByteBuffer bad = ByteBuffer.wrap(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0});
        assertThrows(IllegalStateException.class, () -> VarInt.read(bad));
    }
}
