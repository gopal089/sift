package dev.sift.engine.eval;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Formats eval outcomes as a readable text report and as a tab-separated file (one row per query). */
public final class EvalReport {

    private EvalReport() {
    }

    public static String text(List<Evaluator.Outcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        sb.append("# rank = position of the expected article in the full ranking"
                + " (equal scores: smaller doc id first); \"-\" = did not match at all\n\n");

        sb.append(summaryTable(Evaluator.summarizeByCategory(outcomes)));
        sb.append('\n');

        sb.append(String.format(Locale.US, "%3s  %-14s %6s  %-4s %8s  %-38s %s%n",
                "id", "category", "rank", "mode", "matches", "query -> terms", "expected (top-1 if different)"));
        for (Evaluator.Outcome o : outcomes) {
            String rank = o.rank() == RankMetrics.NOT_FOUND ? "-" : Integer.toString(o.rank());
            String queryAndTerms = "\"" + o.query().query() + "\" -> " + o.terms();
            String expected = o.query().expected();
            if (o.rank() != 1) {
                expected += "   (top-1: " + (o.topTitle().isEmpty() ? "no results" : o.topTitle()) + ")";
            }
            sb.append(String.format(Locale.US, "%3d  %-14s %6s  %-4s %,8d  %-38s %s%n",
                    o.query().id(), o.query().category(), rank, o.mode(), o.matches(), queryAndTerms, expected));
        }
        return sb.toString();
    }

    /** MRR and hit@k overall ("ALL") and per category. hit@k is shown as "count/queries". */
    public static String summaryTable(Map<String, RankMetrics.Summary> summaries) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%-14s %3s  %6s  %6s  %6s  %6s  %9s%n",
                "group", "n", "MRR", "hit@1", "hit@3", "hit@10", "not found"));
        for (Map.Entry<String, RankMetrics.Summary> e : summaries.entrySet()) {
            RankMetrics.Summary s = e.getValue();
            sb.append(String.format(Locale.US, "%-14s %3d  %6.3f  %6s  %6s  %6s  %9d%n",
                    e.getKey(), s.queries(), s.mrr(),
                    count(s.hitAt1(), s.queries()), count(s.hitAt3(), s.queries()),
                    count(s.hitAt10(), s.queries()), s.notFound()));
        }
        return sb.toString();
    }

    public static String tsv(List<Evaluator.Outcome> outcomes) {
        StringBuilder sb = new StringBuilder("id\tcategory\tquery\texpected\trank\tmode\tmatches\ttop1\n");
        for (Evaluator.Outcome o : outcomes) {
            sb.append(o.query().id()).append('\t')
                    .append(o.query().category()).append('\t')
                    .append(o.query().query()).append('\t')
                    .append(o.query().expected()).append('\t')
                    .append(o.rank()).append('\t')
                    .append(o.mode()).append('\t')
                    .append(o.matches()).append('\t')
                    .append(o.topTitle()).append('\n');
        }
        return sb.toString();
    }

    private static String count(double fraction, int n) {
        return Math.round(fraction * n) + "/" + n;
    }
}
