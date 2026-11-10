package dev.sift.engine.eval;

import java.util.List;

/**
 * Quality numbers computed from "where did the expected article rank?" for a group of queries.
 *
 * <p>A rank is 1 for the top result, 2 for the second, and so on. Rank {@link #NOT_FOUND} (0) means
 * the expected article was not among the matching documents at all.
 *
 * <ul>
 *   <li><b>Reciprocal rank</b> of one query = 1 / rank (0 if not found). Rank 1 → 1.0, rank 2 → 0.5, rank 10 → 0.1.</li>
 *   <li><b>MRR</b> (mean reciprocal rank) = the average of those over all queries. 1.0 is perfect.</li>
 *   <li><b>hit@k</b> = the fraction of queries whose expected article is in the top k.</li>
 * </ul>
 * We use full MRR (no cut-off), so rank 458 still counts a tiny 1/458.
 */
public final class RankMetrics {

    public static final int NOT_FOUND = 0;

    private RankMetrics() {
    }

    /** Summary of a group of queries. Fractions are between 0 and 1. */
    public record Summary(int queries, double mrr, double hitAt1, double hitAt3, double hitAt10, int notFound) {
    }

    public static double reciprocalRank(int rank) {
        if (rank < 0) {
            throw new IllegalArgumentException("rank must be >= 0, got " + rank);
        }
        return rank == NOT_FOUND ? 0.0 : 1.0 / rank;
    }

    public static Summary summarize(List<Integer> ranks) {
        if (ranks.isEmpty()) {
            throw new IllegalArgumentException("need at least one query");
        }
        double rrSum = 0;
        int hit1 = 0;
        int hit3 = 0;
        int hit10 = 0;
        int notFound = 0;
        for (int rank : ranks) {
            rrSum += reciprocalRank(rank);
            if (rank == NOT_FOUND) {
                notFound++;
                continue;
            }
            if (rank <= 1) {
                hit1++;
            }
            if (rank <= 3) {
                hit3++;
            }
            if (rank <= 10) {
                hit10++;
            }
        }
        double n = ranks.size();
        return new Summary(ranks.size(), rrSum / n, hit1 / n, hit3 / n, hit10 / n, notFound);
    }
}
