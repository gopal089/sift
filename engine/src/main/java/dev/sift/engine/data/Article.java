package dev.sift.engine.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One line of articles.jsonl. {@code id} is the Wikipedia page id; {@code links} are the titles of the
 * articles this one links to (used for PageRank; may be null in hand-written test data).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Article(int id, String title, String text, List<String> links) {
}
