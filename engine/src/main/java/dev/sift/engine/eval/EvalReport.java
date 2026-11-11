package dev.sift.engine.eval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Formats eval results: a readable comparison of several configs (.txt) and one row per config and query (.tsv).
 */
public final class EvalReport {

    private EvalReport() {
    }

    /** One compared configuration: a short name for the tables and a one-line description. */
    public interface Labeled {
        String name();

        String description();
    }

    public static String comparison(List<? extends Labeled> configs, List<List<Evaluator.Outcome>> outcomes,
                                    List<double[]> medianMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# rank = position of the expected article in the full ranking"
                + " (equal scores: smaller doc id first); \"-\" = did not match at all\n");
        sb.append("# better/worse/same: per query, rank compared with the FIRST config\n\n");

        sb.append("Configs\n");
        for (Labeled c : configs) {
            sb.append(String.format(Locale.US, "  %-12s %s%n", c.name(), c.description()));
        }

        // ---- MRR overall and per category
        List<String> groups = new ArrayList<>(Evaluator.summarizeByCategory(outcomes.get(0)).keySet());
        sb.append("\nMRR per category (the selection rule uses ALL)\n");
        sb.append(String.format(Locale.US, "  %-12s", "config"));
        for (String g : groups) {
            sb.append(String.format(Locale.US, " %13s", g));
        }
        sb.append(String.format(Locale.US, "   %6s %6s %6s %5s   %s%n", "hit@1", "hit@3", "hit@10", "n/f",
                "better/worse/same"));
        for (int i = 0; i < configs.size(); i++) {
            Map<String, RankMetrics.Summary> s = Evaluator.summarizeByCategory(outcomes.get(i));
            sb.append(String.format(Locale.US, "  %-12s", configs.get(i).name()));
            for (String g : groups) {
                sb.append(String.format(Locale.US, " %13.3f", s.get(g).mrr()));
            }
            RankMetrics.Summary all = s.get("ALL");
            int[] bws = betterWorseSame(outcomes.get(0), outcomes.get(i));
            sb.append(String.format(Locale.US, "   %6s %6s %6s %5d   %d/%d/%d%n",
                    count(all.hitAt1(), all.queries()), count(all.hitAt3(), all.queries()),
                    count(all.hitAt10(), all.queries()), all.notFound(), bws[0], bws[1], bws[2]));
        }

        // ---- hit@1 / hit@10 per category
        sb.append("\nhit@1 and hit@10 per category (count/queries)\n");
        sb.append(String.format(Locale.US, "  %-12s", "config"));
        for (String g : groups) {
            sb.append(String.format(Locale.US, " %13s", g));
        }
        sb.append('\n');
        for (int i = 0; i < configs.size(); i++) {
            Map<String, RankMetrics.Summary> s = Evaluator.summarizeByCategory(outcomes.get(i));
            sb.append(String.format(Locale.US, "  %-12s", configs.get(i).name()));
            for (String g : groups) {
                RankMetrics.Summary x = s.get(g);
                sb.append(String.format(Locale.US, " %13s",
                        count(x.hitAt1(), x.queries()) + " " + count(x.hitAt10(), x.queries())));
            }
            sb.append('\n');
        }

        // ---- speed
        sb.append("\nSpeed (ms, K = 10)\n");
        sb.append(String.format(Locale.US, "  %-12s %8s %8s   %s%n", "config", "median", "slowest", "slowest query"));
        for (int i = 0; i < configs.size(); i++) {
            double[] ms = medianMs.get(i);
            int slowest = 0;
            for (int q = 1; q < ms.length; q++) {
                if (ms[q] > ms[slowest]) {
                    slowest = q;
                }
            }
            double[] sorted = ms.clone();
            Arrays.sort(sorted);
            sb.append(String.format(Locale.US, "  %-12s %8.3f %8.3f   \"%s\"%n", configs.get(i).name(),
                    sorted[sorted.length / 2], ms[slowest], outcomes.get(i).get(slowest).query().query()));
        }

        // ---- rank of the expected article, per query and config
        sb.append("\nRank of the expected article\n");
        sb.append(String.format(Locale.US, "  %3s %-14s %-38s", "id", "category", "query"));
        for (Labeled c : configs) {
            sb.append(String.format(Locale.US, " %8s", shorten(c.name(), 8)));
        }
        sb.append("   expected\n");
        List<Evaluator.Outcome> first = outcomes.get(0);
        for (int q = 0; q < first.size(); q++) {
            EvalQuery query = first.get(q).query();
            sb.append(String.format(Locale.US, "  %3d %-14s %-38s", query.id(), query.category(),
                    shorten("\"" + query.query() + "\"", 38)));
            for (List<Evaluator.Outcome> o : outcomes) {
                sb.append(String.format(Locale.US, " %8s", rank(o.get(q).rank())));
            }
            sb.append("   ").append(query.expected()).append('\n');
        }
        return sb.toString();
    }

    /** One row per (config, query): everything needed to diff two runs later. */
    public static String tsv(List<? extends Labeled> configs, List<List<Evaluator.Outcome>> outcomes,
                             List<double[]> medianMs) {
        StringBuilder sb = new StringBuilder(
                "config\tid\tcategory\tquery\texpected\trank\tmode\tmatches\tterms\ttop1\tmedian_ms\n");
        for (int i = 0; i < configs.size(); i++) {
            for (int q = 0; q < outcomes.get(i).size(); q++) {
                Evaluator.Outcome o = outcomes.get(i).get(q);
                sb.append(configs.get(i).name()).append('\t')
                        .append(o.query().id()).append('\t')
                        .append(o.query().category()).append('\t')
                        .append(o.query().query()).append('\t')
                        .append(o.query().expected()).append('\t')
                        .append(o.rank()).append('\t')
                        .append(o.mode()).append('\t')
                        .append(o.matches()).append('\t')
                        .append(String.join(" ", o.terms())).append('\t')
                        .append(o.topTitle()).append('\t')
                        .append(String.format(Locale.US, "%.4f", medianMs.get(i)[q])).append('\n');
            }
        }
        return sb.toString();
    }

    /** How many queries got a better, worse or the same rank than in {@code reference}. Not found counts as worst. */
    static int[] betterWorseSame(List<Evaluator.Outcome> reference, List<Evaluator.Outcome> other) {
        int[] bws = new int[3];
        for (int q = 0; q < reference.size(); q++) {
            long a = sortable(reference.get(q).rank());
            long b = sortable(other.get(q).rank());
            bws[b < a ? 0 : b > a ? 1 : 2]++;
        }
        return bws;
    }

    private static long sortable(int rank) {
        return rank == RankMetrics.NOT_FOUND ? Long.MAX_VALUE : rank;
    }

    private static String rank(int rank) {
        return rank == RankMetrics.NOT_FOUND ? "-" : Integer.toString(rank);
    }

    private static String count(double fraction, int n) {
        return Math.round(fraction * n) + "/" + n;
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
