package dev.sift.engine.vector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Vectors squeezed to 1 byte per number (int8, −127…127) plus one float "scale" per vector:
 * {@code x ≈ scale × b}, with {@code scale = max|x| / 127}. 4× smaller than float32.
 *
 * <p>Example: x = (0.50, −0.25, 0.10). max|x| = 0.5, scale = 0.5/127 = 0.003937.
 * b = round(x / scale) = round(127, −63.5, 25.4) = (127, −63, 25) (Java rounds .5 up).
 * Decoded: (0.500, −0.248, 0.098), close to x.
 *
 * <p>File layout ({@code vectors-i8.bin}): {@code size} little-endian float32 scales, then
 * {@code size × dims} signed bytes.
 */
public final class Int8Vectors implements VectorStore {

    private final ByteBuffer data;     // size × dims bytes
    private final float[] scales;
    private final int dims;

    private Int8Vectors(ByteBuffer data, float[] scales, int dims) {
        this.data = data;
        this.scales = scales;
        this.dims = dims;
    }

    /** Quantises every vector of {@code source}. */
    public static Int8Vectors quantise(VectorStore source) {
        int n = source.size();
        int dims = source.dims();
        byte[] bytes = new byte[n * dims];
        float[] scales = new float[n];
        float[] row = new float[dims];
        for (int d = 0; d < n; d++) {
            source.copy(d, row);
            scales[d] = quantiseRow(row, bytes, d * dims);
        }
        return new Int8Vectors(ByteBuffer.wrap(bytes), scales, dims);
    }

    /** Writes one row's bytes at {@code offset} and returns its scale. */
    static float quantiseRow(float[] row, byte[] out, int offset) {
        float max = 0;
        for (float x : row) {
            max = Math.max(max, Math.abs(x));
        }
        float scale = max == 0 ? 1 : max / 127f;
        for (int i = 0; i < row.length; i++) {
            out[offset + i] = (byte) Math.round(row[i] / scale);   // always within −127…127
        }
        return scale;
    }

    public void write(Path file) throws IOException {
        int n = scales.length;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer head = ByteBuffer.allocate(4 * n).order(ByteOrder.LITTLE_ENDIAN);
            for (float s : scales) {
                head.putFloat(s);
            }
            head.flip();
            while (head.hasRemaining()) {
                ch.write(head);
            }
            ByteBuffer body = data.duplicate();
            body.position(0);
            while (body.hasRemaining()) {
                ch.write(body);
            }
        }
    }

    public static Int8Vectors open(Path file, int size, int dims) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long expected = 4L * size + (long) size * dims;
            if (ch.size() != expected) {
                throw new IOException(file + ": " + ch.size() + " bytes, expected " + expected);
            }
            MappedByteBuffer map = ch.map(FileChannel.MapMode.READ_ONLY, 0, expected);
            map.order(ByteOrder.LITTLE_ENDIAN);
            float[] scales = new float[size];
            map.asFloatBuffer().get(scales);
            ByteBuffer body = map.slice(4 * size, size * dims);
            return new Int8Vectors(body, scales, dims);
        }
    }

    @Override
    public int size() {
        return scales.length;
    }

    @Override
    public int dims() {
        return dims;
    }

    @Override
    public float dot(float[] q, int doc) {
        int base = doc * dims;
        float s0 = 0, s1 = 0, s2 = 0, s3 = 0;
        int i = 0;
        for (; i + 3 < dims; i += 4) {
            s0 += q[i] * data.get(base + i);
            s1 += q[i + 1] * data.get(base + i + 1);
            s2 += q[i + 2] * data.get(base + i + 2);
            s3 += q[i + 3] * data.get(base + i + 3);
        }
        for (; i < dims; i++) {
            s0 += q[i] * data.get(base + i);
        }
        return scales[doc] * ((s0 + s1) + (s2 + s3));
    }

    @Override
    public float dot(int a, int b) {
        int ba = a * dims;
        int bb = b * dims;
        int sum = 0;   // byte × byte sums fit easily in an int (384 × 127 × 127 ≈ 6.2 million)
        for (int i = 0; i < dims; i++) {
            sum += data.get(ba + i) * data.get(bb + i);
        }
        return scales[a] * scales[b] * sum;
    }

    @Override
    public void copy(int doc, float[] out) {
        int base = doc * dims;
        for (int i = 0; i < dims; i++) {
            out[i] = scales[doc] * data.get(base + i);
        }
    }

    @Override
    public long bytes() {
        return (long) scales.length * dims + 4L * scales.length;
    }

    @Override
    public String kind() {
        return "int8";
    }
}
