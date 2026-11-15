package dev.sift.api;

import dev.sift.engine.snippet.Snippet.Highlight;
import java.util.List;
import java.util.Map;

/** The JSON shapes the API returns. Spring (Jackson) turns these records into JSON field by field. */
public final class ApiModels {

    private ApiModels() {
    }

    /**
     * One search result. {@code highlights} are the character ranges of {@code snippet} to show in bold
     * (the query words). The snippet is plain text, never HTML.
     */
    public record Result(int id, String title, String url, String snippet, List<Highlight> highlights,
                         double score) {
    }

    /**
     * Numbers shown in the website's stats panel. {@code embedMs} (turning the query into a vector) and
     * {@code vectorMs} (HNSW search) are null in keyword mode.
     */
    public record QueryStats(double tookMs, int documentsSearched, int totalMatches, List<String> terms,
                             String matchMode, String cache, Double embedMs, Double vectorMs, Double cardMs) {
    }

    /**
     * The definition card shown above the results when the top result is a confident match (see
     * {@code DefinitionRule}). {@code text} is the article's first one or two sentences; {@code matchedBy} is
     * "title" or "redirect"; {@code related} are up to 5 articles nearest in meaning, none of them on this page.
     */
    public record Definition(int id, String title, String url, String text, String matchedBy,
                             List<RelatedArticle> related) {
    }

    public record RelatedArticle(int id, String title, String url) {
    }

    /**
     * Answer of {@code /search}.
     *
     * @param mode     what the client asked for (keyword or smart)
     * @param modeUsed what actually ran (always the same as {@code mode} since Phase 5)
     * @param notice   a short hint for the user, or null
     * @param didYouMean the query with likely spelling mistakes corrected, or null if every word is known
     * @param definition the definition card, or null (only ever on page 1)
     */
    public record SearchResponse(String query, String mode, String modeUsed, String notice, String didYouMean,
                                 int page, int pageSize,
                                 int totalMatches, boolean hasMore, Definition definition, List<Result> results,
                                 QueryStats stats) {
    }

    public record SuggestResponse(String prefix, List<String> suggestions) {
    }

    public record StatsResponse(int documents, int terms, long indexBytes, Map<String, Long> indexFiles,
                                long postingBytesBeforeCompression, long postingBytesAfterCompression,
                                String ranking, String smartRanking, long vectorBytes, Map<String, Object> cache,
                                long queriesServed, double heapUsedMb, double startupSeconds) {
    }

    /** Every error has the same shape: {@code {"error": {"code": "...", "message": "..."}}}. */
    public record ErrorResponse(ErrorBody error) {
        public static ErrorResponse of(String code, String message) {
            return new ErrorResponse(new ErrorBody(code, message));
        }
    }

    public record ErrorBody(String code, String message) {
    }
}
