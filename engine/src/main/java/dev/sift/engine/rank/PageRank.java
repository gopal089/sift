package dev.sift.engine.rank;

import java.util.Arrays;

/**
 * PageRank: a page is important if important pages link to it.
 *
 * <p>Picture a "random surfer". On each step, with probability d = 0.85 they click a random link on the
 * current page; otherwise (0.15) they jump to a random page. A page's PageRank is the share of time the
 * surfer spends on it. We compute it by repeating one update until the numbers stop changing:
 * <pre>
 *   new[v] = (1 − d) / N                          the random jump
 *          + d × (rank of all dangling pages) / N  a page with no links: the surfer jumps anywhere
 *          + d × Σ rank[u] / outDegree(u)          over all pages u that link to v
 * </pre>
 * All ranks always add up to 1. We stop when the total change of one round is below {@code epsilon},
 * or after {@code maxRounds}.
 */
public final class PageRank {

    private PageRank() {
    }

    /** @param change the sum of |new − old| over all pages in the last round */
    public record Result(double[] ranks, int rounds, double change) {
    }

    public static Result compute(LinkGraph graph, double damping, double epsilon, int maxRounds) {
        int n = graph.pageCount();
        double[] rank = new double[n];
        Arrays.fill(rank, 1.0 / n);   // start: every page equally likely
        double[] next = new double[n];
        int rounds = 0;
        double change = Double.MAX_VALUE;
        while (rounds < maxRounds && change >= epsilon) {
            double dangling = 0;
            for (int u = 0; u < n; u++) {
                if (graph.outDegree(u) == 0) {
                    dangling += rank[u];
                }
            }
            Arrays.fill(next, (1 - damping) / n + damping * dangling / n);
            for (int u = 0; u < n; u++) {
                int out = graph.outDegree(u);
                if (out > 0) {
                    double share = damping * rank[u] / out;   // u gives each page it links to an equal share
                    for (int i = 0; i < out; i++) {
                        next[graph.target(u, i)] += share;
                    }
                }
            }
            change = 0;
            for (int v = 0; v < n; v++) {
                change += Math.abs(next[v] - rank[v]);
            }
            double[] swap = rank;
            rank = next;
            next = swap;
            rounds++;
        }
        return new Result(rank, rounds, change);
    }

    /**
     * Turns raw ranks into a number between 0 and 1 for ranking: 0 for the least important page, 1 for the
     * most important. Raw PageRank differs by factors of thousands between pages, so we take the logarithm
     * first, then scale: (ln r − ln min) / (ln max − ln min).
     */
    public static double[] normalisedLog(double[] ranks) {
        double[] out = new double[ranks.length];
        double min = Double.MAX_VALUE;
        double max = 0;
        for (double r : ranks) {
            min = Math.min(min, r);
            max = Math.max(max, r);
        }
        if (ranks.length == 0 || min <= 0 || max <= min) {
            return out;   // no ranks, or all equal: no page gets a boost
        }
        double logMin = Math.log(min);
        double range = Math.log(max) - logMin;
        for (int i = 0; i < ranks.length; i++) {
            out[i] = (Math.log(ranks[i]) - logMin) / range;
        }
        return out;
    }
}
