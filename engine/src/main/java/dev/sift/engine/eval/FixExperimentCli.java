package dev.sift.engine.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sift.engine.embed.QueryEmbedder;
import dev.sift.engine.search.QueryRanker;
import dev.sift.engine.search.Ranking;
import dev.sift.engine.search.Searcher;
import dev.sift.engine.search.SmartSearcher;
import dev.sift.engine.store.DiskIndex;
import dev.sift.engine.vector.ExactIndex;
import dev.sift.engine.vector.Int8Vectors;
import dev.sift.engine.vector.VectorFiles;
import dev.sift.engine.vector.VectorStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one pre-registered Phase 5 fix experiment (rules in PROGRESS.md, commit e3c9e6b, written before any run).
 *
 * <p>Five embedding variants × two fusions (plain RRF, keyword weight 2), all with EXACT brute-force search over
 * int8 vectors, on the main 50 queries. The selection rule is applied in code, so it cannot drift:
 * <ol>
 *   <li>highest overall hybrid MRR wins;</li>
 *   <li>everything within 0.01 of the best is a tie → prefer one vector over two, then MiniLM over bge,
 *       then plain RRF over weighted (then higher MRR);</li>
 *   <li>Photosynthesis for "how do plants make food" is reported, never used to choose.</li>
 * </ol>
 * Keyword and semantic-only rows are printed for information; they are not candidates.
 *
 * <pre>scripts/sift-run eval.FixExperimentCli data/index eval/queries.json fix-variants</pre>
 * Writes {@code eval/results/LABEL.txt} and {@code .tsv}.
 */
public final class FixExperimentCli {

    static final double TIE = 0.01;
    static final double[] WEIGHTS = {1, 2};
    static final String PHOTO_QUERY = "how do plants make food";

    /** One embedding variant: which model, which vector folders (one or two vectors per article). */
    record Variant(String id, String model, Path modelDir, List<Path> dirs, String text) {
        boolean bge() {
            return model.startsWith("bge");
        }
    }

    static final List<Variant> VARIANTS = List.of(
            new Variant("A", "MiniLM", Path.of("data/model"), List.of(Path.of("data/vectors")),
                    "title + 200 words"),
            new Variant("B", "MiniLM", Path.of("data/model"), List.of(Path.of("data/vectors-minilm60")),
                    "title + 60 words"),
            new Variant("C", "MiniLM", Path.of("data/model"),
                    List.of(Path.of("data/vectors-minilm60"), Path.of("data/vectors")), "60 + 200 words, max"),
            new Variant("D", "bge-small", Path.of("data/model-bge"), List.of(Path.of("data/vectors-bge60")),
                    "title + 60 words, query prefix"),
            new Variant("E", "bge-small", Path.of("data/model-bge"),
                    List.of(Path.of("data/vectors-bge60"), Path.of("data/vectors-bge200")),
                    "60 + 200 words, max, query prefix"));

    /** A row of the report. {@code variant} is null for the keyword baseline; {@code weight} 0 = semantic only. */
    record Config(String name, QueryRanker ranker, Variant variant, double weight) implements EvalReport.Labeled {
        @Override
        public String description() {
            return ranker.description();
        }

        boolean candidate() {
            return variant != null && weight > 0;
        }
    }

    /** Pre-registered tie-break order for configs within {@link #TIE} of the best MRR. */
    static final Comparator<Candidate> TIE_BREAK = Comparator
            .comparingInt((Candidate c) -> c.variant().dirs().size())        // one vector before two
            .thenComparing(c -> c.variant().bge())                            // MiniLM (false) before bge (true)
            .thenComparingDouble(Candidate::weight)                           // plain (1) before weighted (2)
            .thenComparing(Comparator.comparingDouble(Candidate::mrr).reversed());

    record Candidate(String name, Variant variant, double weight, double mrr) {
    }

    /** Applies rules 1 and 2. Returns the winner; {@code tied} receives every config within the tie margin. */
    static Candidate select(List<Candidate> candidates, List<Candidate> tied) {
        double best = candidates.stream().mapToDouble(Candidate::mrr).max().orElseThrow();
        candidates.stream().filter(c -> best - c.mrr() <= TIE + 1e-12).sorted(TIE_BREAK).forEach(tied::add);
        return tied.get(0);
    }

    private FixExperimentCli() {
    }

    public static void main(String[] args) throws IOException {
        DiskIndex index = DiskIndex.open(Path.of(args[0]));
        Path queriesPath = Path.of(args[1]);
        String label = args[2];
        List<EvalQuery> queries = Arrays.asList(new ObjectMapper().readValue(Files.readAllBytes(queriesPath),
                EvalQuery[].class));
        Searcher keyword = new Searcher(index, Ranking.DEFAULT);

        // int8 copies, quantised in memory exactly as HnswBuildCli does (one per folder, shared by C and E)
        Map<Path, VectorStore> int8 = new HashMap<>();
        StringBuilder sb = new StringBuilder("# Phase 5 fix experiment: 5 embedding variants x 2 fusions\n")
                .append(RunInfo.header());
        for (Variant v : VARIANTS) {
            for (Path dir : v.dirs()) {
                if (!int8.containsKey(dir)) {
                    VectorFiles.Meta meta = VectorFiles.checkMatches(dir, index.docs());
                    int8.put(dir, Int8Vectors.quantise(VectorFiles.open(dir, index.docs(), "float32")));
                    sb.append(String.format(Locale.US, "# %s: %s%n", dir, meta));
                }
            }
        }
        sb.append(String.format(Locale.US, "# queries: %s (%d); semantic part = EXACT brute force over int8 vectors;"
                + " RRF k = %d, lists of %d%n", queriesPath, queries.size(), SmartSearcher.RRF_K,
                SmartSearcher.LIST_SIZE));
        sb.append("# rules (pre-registered, PROGRESS.md, commit e3c9e6b): highest hybrid MRR wins; within " + TIE
                + " = tie -> one vector > two, MiniLM > bge, plain RRF > weighted\n\n");

        List<Config> configs = new ArrayList<>();
        configs.add(new Config("keyword", keyword, null, 0));
        List<QueryEmbedder> embedders = new ArrayList<>();
        Map<Path, QueryEmbedder> byModel = new HashMap<>();
        try {
            for (Variant v : VARIANTS) {
                QueryEmbedder e = byModel.get(v.modelDir());
                if (e == null) {
                    e = QueryEmbedder.open(v.modelDir());
                    byModel.put(v.modelDir(), e);
                    embedders.add(e);
                }
                VectorStore[] stores = v.dirs().stream().map(int8::get).toArray(VectorStore[]::new);
                ExactIndex exact = new ExactIndex(v.id() + " " + v.model() + " " + v.text(), stores);
                configs.add(new Config(v.id() + "-sem", new SmartSearcher(keyword, e, exact, index.docs(),
                        SmartSearcher.Mode.SEMANTIC, 1), v, 0));
                for (double w : WEIGHTS) {
                    String name = v.id() + (w == 1 ? "-rrf" : "-rrf-w2");
                    configs.add(new Config(name, new SmartSearcher(keyword, e, exact, index.docs(),
                            SmartSearcher.Mode.HYBRID, w), v, w));
                }
            }

            List<List<Evaluator.Outcome>> outcomes = new ArrayList<>();
            List<double[]> ms = new ArrayList<>();
            Evaluator evaluator = new Evaluator(index.docs());
            for (Config c : configs) {
                System.out.println("running " + c.name());
                outcomes.add(evaluator.run(c.ranker(), queries));
                ms.add(timeOnce(c.ranker(), queries));
            }
            sb.append("# speed column = one top-10 search per query after the eval pass (exact search is slow by"
                    + " design; HNSW is used in the server)\n");
            sb.append(EvalReport.comparison(configs, outcomes, ms));

            // ---- the selection rule
            List<Candidate> candidates = new ArrayList<>();
            sb.append("\nSelection (hybrid configs only, overall MRR on the main ").append(queries.size())
                    .append(")\n");
            for (int i = 0; i < configs.size(); i++) {
                Config c = configs.get(i);
                if (c.candidate()) {
                    double mrr = Evaluator.summarizeByCategory(outcomes.get(i)).get("ALL").mrr();
                    candidates.add(new Candidate(c.name(), c.variant(), c.weight(), mrr));
                    sb.append(String.format(Locale.US, "  %-10s MRR %.5f%n", c.name(), mrr));
                }
            }
            List<Candidate> tied = new ArrayList<>();
            Candidate winner = select(candidates, tied);
            double best = candidates.stream().mapToDouble(Candidate::mrr).max().orElseThrow();
            sb.append(String.format(Locale.US, "Rule 1: best MRR = %.5f (%s)%n", best, candidates.stream()
                    .filter(c -> c.mrr() == best).map(Candidate::name).toList()));
            sb.append(String.format(Locale.US, "Rule 2: within %.2f of the best (MRR >= %.5f), in tie-break order: %s%n",
                    TIE, best - TIE, tied.stream().map(c -> String.format(Locale.US, "%s (%.5f)", c.name(), c.mrr()))
                            .toList()));
            sb.append(String.format(Locale.US, "WINNER: %s = variant %s (%s, %s), %s, MRR %.5f%n", winner.name(),
                    winner.variant().id(), winner.variant().model(), winner.variant().text(),
                    winner.weight() == 1 ? "plain RRF" : "keyword weight " + winner.weight(), winner.mrr()));

            // ---- rule 3: reported only
            sb.append("\nRule 3 (reported, not used to choose): rank of Photosynthesis for \"" + PHOTO_QUERY + "\"\n");
            for (int i = 0; i < configs.size(); i++) {
                for (Evaluator.Outcome o : outcomes.get(i)) {
                    if (o.query().query().equals(PHOTO_QUERY)) {
                        String r = o.rank() == RankMetrics.NOT_FOUND ? "-" : "#" + o.rank();
                        sb.append(String.format(Locale.US, "  %-10s %-4s %s%n", configs.get(i).name(), r,
                                o.rank() != RankMetrics.NOT_FOUND && o.rank() <= 3 ? "top 3" : "not top 3"));
                    }
                }
            }

            Path out = Path.of("eval/results");
            Files.writeString(out.resolve(label + ".txt"), sb.toString());
            Files.writeString(out.resolve(label + ".tsv"), EvalReport.tsv(configs, outcomes, ms));
            System.out.print(sb);
        } finally {
            for (QueryEmbedder e : embedders) {
                e.close();
            }
        }
    }

    private static double[] timeOnce(QueryRanker ranker, List<EvalQuery> queries) {
        double[] ms = new double[queries.size()];
        for (int i = 0; i < queries.size(); i++) {
            long s = System.nanoTime();
            ranker.search(queries.get(i).query(), 10);
            ms[i] = (System.nanoTime() - s) / 1e6;
        }
        return ms;
    }
}
