package dev.sift.engine.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArticleReaderTest {

    @Test
    void readsJsonLinesAndIgnoresLinks(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("articles.jsonl");
        Files.writeString(file, """
                {"id": 1, "title": "April", "text": "April is a month.", "links": ["Month"]}
                {"id": 3518, "title": "Photosynthesis", "text": "Plants make food.\\nLine two.", "links": []}
                """, StandardCharsets.UTF_8);

        List<Article> articles = new ArrayList<>();
        int count = ArticleReader.forEach(file, articles::add);

        assertEquals(2, count);
        assertEquals(new Article(1, "April", "April is a month."), articles.get(0));
        assertEquals(new Article(3518, "Photosynthesis", "Plants make food.\nLine two."), articles.get(1));
    }
}
