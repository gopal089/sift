package dev.sift.engine.eval;

/**
 * One line of {@code eval/queries.json}: a query and the one article we expect to be the best answer.
 *
 * @param id       1..50, stable so results from different runs can be compared line by line
 * @param query    what a user would type
 * @param expected the exact title of the expected article (must exist in articles.jsonl)
 * @param category group used for per-category numbers (diagnostic, famous-single, ...)
 * @param note     why this query is interesting (not used in scoring)
 */
public record EvalQuery(int id, String query, String expected, String category, String note) {
}
