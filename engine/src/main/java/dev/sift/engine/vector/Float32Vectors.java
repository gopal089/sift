package dev.sift.engine.vector;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Vectors as 32-bit floats, 4 bytes per number. The file is memory-mapped: the operating system loads
 * pages when they are first read, and they live outside the Java heap.
 */
public final class Float32Vectors implements VectorStore {

    private final FloatBuffer data;
    private final int size;
    private final int dims;

    private Float32Vectors(FloatBuffer data, int size, int dims) {
        this.data = data;
        this.size = size;
        this.dims = dims;
    }

    /** For tests and tools: vectors already in memory, row after row. */
    public static Float32Vectors of(float[] rows, int dims) {
        if (rows.length % dims != 0) {
            throw new IllegalArgumentException("length is not a multiple of dims");
        }
        return new Float32Vectors(FloatBuffer.wrap(rows), rows.length / dims, dims);
    }

    /** Maps a file of little-endian float32 rows. */
    public static Float32Vectors open(Path file, int dims) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long bytes = ch.size();
            if (bytes % (4L * dims) != 0) {
                throw new IOException(file + ": size " + bytes + " is not a whole number of " + dims + "-float rows");
            }
            if (bytes > Integer.MAX_VALUE) {
                throw new IOException(file + " is over 2 GB; one mapping cannot hold it");
            }
            MappedByteBuffer map = ch.map(FileChannel.MapMode.READ_ONLY, 0, bytes);
            map.order(ByteOrder.LITTLE_ENDIAN);
            return new Float32Vectors(map.asFloatBuffer(), (int) (bytes / (4L * dims)), dims);
        }
    }

    @Override
    public int size() {
        return size;
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
        return (s0 + s1) + (s2 + s3);
    }

    @Override
    public float dot(int a, int b) {
        int ba = a * dims;
        int bb = b * dims;
        float s0 = 0, s1 = 0;
        int i = 0;
        for (; i + 1 < dims; i += 2) {
            s0 += data.get(ba + i) * data.get(bb + i);
            s1 += data.get(ba + i + 1) * data.get(bb + i + 1);
        }
        for (; i < dims; i++) {
            s0 += data.get(ba + i) * data.get(bb + i);
        }
        return s0 + s1;
    }

    @Override
    public void copy(int doc, float[] out) {
        data.get(doc * dims, out, 0, dims);
    }

    @Override
    public long bytes() {
        return 4L * size * dims;
    }

    @Override
    public String kind() {
        return "float32";
    }
}
