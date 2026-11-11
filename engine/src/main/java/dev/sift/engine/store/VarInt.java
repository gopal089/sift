package dev.sift.engine.store;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Variable-length integers ("varints"): small numbers take fewer bytes.
 *
 * <p>Each byte carries 7 bits of the number, lowest bits first. The top bit of a byte is 1 if more bytes
 * follow and 0 on the last byte.
 * <pre>
 *   5      = 0000101                 → 05            (1 byte)
 *   300    = 10 0101100              → AC 02         (0101100 + "more" bit = AC, then 10 = 02)
 *   &lt; 128 → 1 byte, &lt; 16,384 → 2 bytes, &lt; 2,097,152 → 3 bytes, ... at most 5 bytes for an int
 * </pre>
 * Only non-negative ints are stored (doc-id gaps, counts and position gaps are never negative).
 */
public final class VarInt {

    private VarInt() {
    }

    /** Writes {@code value} (must be &gt;= 0) and returns how many bytes it took. */
    public static int write(OutputStream out, int value) throws IOException {
        if (value < 0) {
            throw new IllegalArgumentException("varint must be >= 0, got " + value);
        }
        int bytes = 1;
        while (value >= 0x80) {              // more than 7 bits left
            out.write((value & 0x7F) | 0x80); // low 7 bits + "more follows"
            value >>>= 7;
            bytes++;
        }
        out.write(value);                     // last byte: top bit 0
        return bytes;
    }

    /** Reads one varint at the buffer's current position and moves the position past it. */
    public static int read(ByteBuffer in) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {   // 5 bytes × 7 bits covers 32 bits
            int b = in.get();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalStateException("corrupt varint: more than 5 bytes");
    }

    /** How many bytes {@link #write} uses for {@code value}. */
    public static int size(int value) {
        int bytes = 1;
        while (value >= 0x80) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }
}
