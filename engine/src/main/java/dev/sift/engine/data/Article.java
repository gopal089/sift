package dev.sift.engine.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One line of articles.jsonl. {@code id} is the Wikipedia page id; {@code links} are ignored for now (PageRank, Phase 4). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Article(int id, String title, String text) {
}
