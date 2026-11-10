package dev.sift.engine.data;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Streams a JSON Lines file of articles, one at a time, so the whole file is never in memory. */
public final class ArticleReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ArticleReader() {
    }

    /** Calls {@code sink} for each article in file order. Returns how many were read. */
    public static int forEach(Path jsonl, Consumer<Article> sink) throws IOException {
        int count = 0;
        try (MappingIterator<Article> it = MAPPER.readerFor(Article.class).readValues(jsonl.toFile())) {
            while (it.hasNext()) {
                sink.accept(it.next());
                count++;
            }
        }
        return count;
    }
}
