package dev.sift.api;

import dev.sift.api.ApiModels.QueryStats;
import dev.sift.api.ApiModels.Result;
import dev.sift.api.ApiModels.SearchResponse;
import dev.sift.api.ApiModels.StatsResponse;
import dev.sift.api.ApiModels.SuggestResponse;
import dev.sift.engine.search.SearchResult;
import dev.sift.engine.search.SearchResult.Hit;
import dev.sift.engine.search.Searcher;
import dev.sift.engine.snippet.Snippet;
import dev.sift.engine.snippet.SnippetBuilder;
import dev.sift.engine.store.DiskIndex;
import dev.sift.engine.store.IndexMeta;
import dev.sift.engine.suggest.TitleSuggester;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

/** The four HTTP endpoints. Each method checks its input, calls the engine and returns a record as JSON. */
@RestController
public class SearchController {

    static final int PAGE_SIZE = 10;
    static final int MAX_PAGE = 100;            // deepest result we serve is number 1,000
    static final int MAX_QUERY_LENGTH = 200;
    static final int MAX_SUGGESTIONS = 5;
    static final int SNIPPET_WORDS = 30;
    private static final String WIKI_URL = "https://simple.wikipedia.org/wiki/";

    private final DiskIndex index;
    private final Searcher searcher;
    private final TitleSuggester suggester;
    private final AtomicLong queriesServed = new AtomicLong();   // a counter that is safe with many threads
    private volatile double startupSeconds;

    public SearchController(DiskIndex index, Searcher searcher, TitleSuggester suggester) {
        this.index = index;
        this.searcher = searcher;
        this.suggester = suggester;
    }

    /** Spring calls this once when the server is ready: remember how long the whole start took. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        startupSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0;
    }

    @GetMapping("/search")
    public SearchResponse search(@RequestParam(name = "q") String q,
                                 @RequestParam(name = "mode", defaultValue = "keyword") String mode,
                                 @RequestParam(name = "page", defaultValue = "1") int page) {
        String query = q.strip();
        if (query.isEmpty()) {
            throw new BadRequestException("EMPTY_QUERY", "Type something to search for.");
        }
        if (query.codePointCount(0, query.length()) > MAX_QUERY_LENGTH) {
            throw new BadRequestException("QUERY_TOO_LONG",
                    "Your search is too long. Please use at most " + MAX_QUERY_LENGTH + " characters.");
        }
        if (!mode.equals("keyword") && !mode.equals("smart")) {
            throw new BadRequestException("BAD_MODE", "'mode' must be 'keyword' or 'smart'.");
        }
        if (page < 1 || page > MAX_PAGE) {
            throw new BadRequestException("BAD_PAGE", "'page' must be between 1 and " + MAX_PAGE + ".");
        }

        long start = System.nanoTime();
        // Ask for everything up to the end of this page, then keep only this page's slice.
        SearchResult result = searcher.search(query, page * PAGE_SIZE);
        List<Hit> hits = result.hits();
        int from = Math.min((page - 1) * PAGE_SIZE, hits.size());
        List<Result> results = new ArrayList<>();
        for (Hit hit : hits.subList(from, hits.size())) {
            // the best 30-word window around the query words, chosen with the term positions from the index
            Snippet snippet = SnippetBuilder.build(index, hit.docId(), result.terms(), index.text(hit.docId()),
                    SNIPPET_WORDS);
            results.add(new Result(hit.wikiId(), hit.title(), wikiUrl(hit.title()), snippet.text(),
                    snippet.highlights(), hit.score()));
        }
        double tookMs = (System.nanoTime() - start) / 1e6;
        queriesServed.incrementAndGet();

        String notice = null;
        if (result.terms().isEmpty()) {
            notice = "Your search has no letters or numbers to look for. Try a word like 'volcano'.";
        } else if (mode.equals("smart")) {
            notice = "Smart (meaning) search is not built yet (Phase 5). Showing keyword results.";
        }
        QueryStats stats = new QueryStats(tookMs, index.docs().size(), result.totalMatches(), result.terms(),
                result.mode().name(), "off");
        return new SearchResponse(query, mode, "keyword", notice, page, PAGE_SIZE, result.totalMatches(),
                result.totalMatches() > page * PAGE_SIZE, results, stats);
    }

    @GetMapping("/suggest")
    public SuggestResponse suggest(@RequestParam(name = "prefix", defaultValue = "") String prefix) {
        if (prefix.codePointCount(0, prefix.length()) > MAX_QUERY_LENGTH) {
            throw new BadRequestException("PREFIX_TOO_LONG",
                    "Please use at most " + MAX_QUERY_LENGTH + " characters.");
        }
        return new SuggestResponse(prefix, suggester.suggest(prefix, MAX_SUGGESTIONS));
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        IndexMeta meta = index.meta();
        long before = 0;
        long after = 0;
        for (Map.Entry<String, Long> e : meta.rawBytes().entrySet()) {
            before += e.getValue();
            after += meta.compressedBytes().get(e.getKey());
        }
        Runtime rt = Runtime.getRuntime();
        return new StatsResponse(meta.documents(), meta.terms(), meta.totalBytes(), meta.fileSizes(), before, after,
                searcher.description(), Map.of("enabled", false, "note", "the cache arrives in Phase 6"),
                queriesServed.get(), (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0), startupSeconds);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "documents", index.docs().size());
    }

    /** "World War II" → https://simple.wikipedia.org/wiki/World_War_II (special characters percent-encoded). */
    static String wikiUrl(String title) {
        return WIKI_URL + UriUtils.encodePathSegment(title.replace(' ', '_'), StandardCharsets.UTF_8);
    }
}
