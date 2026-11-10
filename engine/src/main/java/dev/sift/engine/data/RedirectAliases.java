package dev.sift.engine.data;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns {@code redirects.jsonl} ("USA" → "United States") into alternate titles per article:
 * {@code aliasesOf("United States") = ["USA", "U.S.", "United States of America", ...]}.
 *
 * <p>Redirect chains are followed (A → B → C gives C the aliases A and B), with a hop limit so a loop
 * can never hang us. Whether the final target is a real article is decided later: only articles ask
 * for their aliases, so redirects ending at a skipped page are simply never used.
 */
public final class RedirectAliases {

    /** Longest chain we follow. Phase 0 found exactly 1 link needing 2+ hops and no loops. */
    static final int MAX_HOPS = 10;

    private final Map<String, List<String>> aliasesByTarget;

    private RedirectAliases(Map<String, List<String>> aliasesByTarget) {
        this.aliasesByTarget = aliasesByTarget;
    }

    public record Redirect(String from, String to) {
    }

    public static RedirectAliases load(Path jsonl) throws IOException {
        List<Redirect> redirects = new ArrayList<>();
        try (MappingIterator<Redirect> it = new ObjectMapper().readerFor(Redirect.class).readValues(jsonl.toFile())) {
            while (it.hasNext()) {
                redirects.add(it.next());
            }
        }
        return of(redirects);
    }

    public static RedirectAliases of(List<Redirect> redirects) {
        Map<String, String> next = new HashMap<>(redirects.size() * 2);
        for (Redirect r : redirects) {
            next.put(r.from(), r.to());
        }
        Map<String, List<String>> byTarget = new HashMap<>();
        for (Redirect r : redirects) {
            String target = finalTarget(r.to(), next);
            if (target != null && !target.equals(r.from())) {
                byTarget.computeIfAbsent(target, t -> new ArrayList<>()).add(r.from());
            }
        }
        return new RedirectAliases(byTarget);
    }

    /** Follows redirects from {@code title}; null if the chain is longer than {@link #MAX_HOPS} (e.g. a loop). */
    private static String finalTarget(String title, Map<String, String> next) {
        String t = title;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            String n = next.get(t);
            if (n == null) {
                return t;
            }
            t = n;
        }
        return null;
    }

    /** Alternate titles of an article, in file order (empty if none). */
    public List<String> aliasesOf(String articleTitle) {
        return aliasesByTarget.getOrDefault(articleTitle, List.of());
    }

    /** How many redirects ended at some title (article or not). */
    public int size() {
        int n = 0;
        for (List<String> list : aliasesByTarget.values()) {
            n += list.size();
        }
        return n;
    }
}
