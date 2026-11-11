package dev.sift.engine.search;

import java.util.Locale;

/**
 * Search settings: which scoring formula, its parameters, and how query words are matched.
 *
 * <ul>
 *   <li>{@link Model#BM25}: one field (everything together), the Phase 1 baseline. Uses {@code bBody} as b.</li>
 *   <li>{@link Model#BM25F}: title and body counts combined first, saturated once.</li>
 *   <li>{@link Model#BM25_SUM}: BM25(body) + wTitle × BM25(title), each saturated separately.</li>
 * </ul>
 * On top of any model, a query term found among a document's alias words adds {@code wAlias × idf}.
 *
 * @param k1                saturation: how fast extra mentions stop helping
 * @param bBody             length normalisation of the body (0 = none, 1 = full)
 * @param bTitle            length normalisation of the title
 * @param wTitle            how much the title counts compared with the body
 * @param wAlias            bonus weight when a term is one of the document's alias (redirect) words; 0 = off
 * @param softAnd           false: AND, with OR only if AND finds nothing. true: documents with all terms
 *                          first, then documents with fewer terms (more matched terms first, then score)
 * @param dropQuestionWords treat who/what/when/where/why/how/did/does/is as stop words in queries
 */
public record Ranking(Model model, double k1, double bBody, double bTitle, double wTitle, double wAlias,
                      boolean softAnd, boolean dropQuestionWords) {

    public enum Model { BM25, BM25F, BM25_SUM }

    /** The Phase 1 ranking: single-field BM25, k1 = 1.2, b = 0.75, strict AND. */
    public static final Ranking BASELINE = new Ranking(Model.BM25, 1.2, 0.75, 0.75, 1.0, 0.0, false, false);

    /** What {@link Searcher} uses unless told otherwise. */
    public static final Ranking DEFAULT = BASELINE;

    public Ranking {
        if (k1 <= 0 || bBody < 0 || bBody > 1 || bTitle < 0 || bTitle > 1 || wTitle < 0 || wAlias < 0) {
            throw new IllegalArgumentException("need k1 > 0, 0 <= b <= 1, weights >= 0");
        }
    }

    /**
     * Parses a short spec such as {@code "bm25sum:w=2,bb=0.3,wa=1,soft=1,qstop=1"}. Missing keys keep their
     * {@link #BASELINE} values. Keys: {@code w} (title weight), {@code bb} (body b), {@code bt} (title b),
     * {@code k1}, {@code wa} (alias weight), {@code soft} (1 = soft AND), {@code qstop} (1 = drop question words).
     */
    public static Ranking parse(String spec) {
        String[] modelAndParams = spec.split(":", 2);
        Model model = switch (modelAndParams[0].strip().toLowerCase(Locale.ROOT)) {
            case "bm25" -> Model.BM25;
            case "bm25f" -> Model.BM25F;
            case "bm25sum" -> Model.BM25_SUM;
            default -> throw new IllegalArgumentException("unknown model in \"" + spec + "\"");
        };
        double k1 = BASELINE.k1;
        double bBody = BASELINE.bBody;
        double bTitle = BASELINE.bTitle;
        double wTitle = BASELINE.wTitle;
        double wAlias = BASELINE.wAlias;
        boolean softAnd = BASELINE.softAnd;
        boolean dropQuestionWords = BASELINE.dropQuestionWords;
        if (modelAndParams.length == 2 && !modelAndParams[1].isBlank()) {
            for (String kv : modelAndParams[1].split(",")) {
                String[] p = kv.split("=", 2);
                if (p.length != 2) {
                    throw new IllegalArgumentException("expected key=value, got \"" + kv + "\"");
                }
                double v = Double.parseDouble(p[1].strip());
                switch (p[0].strip()) {
                    case "k1" -> k1 = v;
                    case "bb" -> bBody = v;
                    case "bt" -> bTitle = v;
                    case "w" -> wTitle = v;
                    case "wa" -> wAlias = v;
                    case "soft" -> softAnd = v != 0;
                    case "qstop" -> dropQuestionWords = v != 0;
                    default -> throw new IllegalArgumentException("unknown key \"" + p[0] + "\" in \"" + spec + "\"");
                }
            }
        }
        return new Ranking(model, k1, bBody, bTitle, wTitle, wAlias, softAnd, dropQuestionWords);
    }

    @Override
    public String toString() {
        String scoring = switch (model) {
            case BM25 -> String.format(Locale.US, "BM25 k1=%s b=%s (one field)", num(k1), num(bBody));
            case BM25F -> String.format(Locale.US, "BM25F k1=%s w_title=%s b_title=%s b_body=%s",
                    num(k1), num(wTitle), num(bTitle), num(bBody));
            case BM25_SUM -> String.format(Locale.US, "BM25(body) + w*BM25(title) k1=%s w=%s b_title=%s b_body=%s",
                    num(k1), num(wTitle), num(bTitle), num(bBody));
        };
        return scoring
                + (wAlias > 0 ? " + alias bonus w_alias=" + num(wAlias) : "")
                + (softAnd ? " | soft AND" : " | AND, else OR")
                + (dropQuestionWords ? " | question words dropped" : "");
    }

    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
