package dev.sift.engine.search;

/** Anything that turns a query into a ranked list: the keyword {@link Searcher} or the {@link SmartSearcher}. */
public interface QueryRanker {

    /** The top {@code k} documents for {@code query}, best first. */
    SearchResult search(String query, int k);

    /** One line describing the settings, written into results files. */
    String description();
}
