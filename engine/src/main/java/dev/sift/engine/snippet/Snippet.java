package dev.sift.engine.snippet;

import java.util.List;

/**
 * A short piece of an article to show under a result.
 *
 * @param text       plain text (never HTML); may start or end with "…" if it was cut out of the middle
 * @param highlights the parts of {@code text} that are query words, in order, never overlapping
 */
public record Snippet(String text, List<Highlight> highlights) {

    /** Characters {@code [start, end)} of the snippet text (UTF-16 units, as in Java and JavaScript strings). */
    public record Highlight(int start, int end) {
    }
}
