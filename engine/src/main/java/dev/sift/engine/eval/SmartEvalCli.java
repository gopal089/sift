package dev.sift.engine.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sift.engine.embed.QueryEmbedder;
import dev.sift.engine.search.QueryRanker;
import dev.sift.engine.search.Ranking;
import dev.sift.engine.search.Searcher;
import dev.sift.engine.search.SmartSearcher;
import dev.sift.engine.store.DiskIndex;
import dev.sift.engine.vector.Hnsw;
import dev.sift.engine.vector.HnswIndex;
import dev.sift.engine.vector.VectorFiles;
import dev.sift.engine.vector.VectorStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Compares keyword, semantic and hybrid ranking on an eval query file (MRR overall and per category).
 *
 * <pre>scripts/sift-run eval.SmartEvalCli data/index data/vectors data/model eval/queries.json LABEL
 *        --ef 100 --modes keyword,semantic-f32,hybrid-f32,semantic-i8,hybrid-i8</pre>
 * Writes {@code eval/results/LABEL.txt} and {@code .tsv}.
 */
public final class SmartEvalCli {

    private static final int REPEAT = 5;

    record Config(String name, QueryRanker ranker) implements EvalReport.Labeled {
        @Override
        public String description() {
            return ranker.description();
        }
    }

    private SmartEvalCli() {
    }

    public static void main(String[] args) throws IOException {
        DiskIndex index = DiskIndex.open(Path.of(args[0]));
        Path vectorDir = Path.of(args[1]);
        Path modelDir = Path.of(args[2]);
        Path queriesPath = Path.of(args[3]);
        String label = args[4];
        int ef = 100;
        List<String> modes = List.of("keyword", "semantic-f32", "hybrid-f32");
        for (int i = 5; i < args.length; i++) {
            switch (args[i]) {
                case "--ef" -> ef = Integer.parseInt(args[++i]);
                case "--modes" -> modes = Arrays.asList(args[++i].split(","));
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        byte[] queryBytes = Files.readAllBytes(queriesPath);
        List<EvalQuery> queries = Arrays.asList(new ObjectMapper().readValue(queryBytes, EvalQuery[].class));

        Searcher keyword = new Searcher(index, Ranking.DEFAULT);
        try (QueryEmbedder embedder = QueryEmbedder.open(modelDir)) {
            Hnsw graphF32 = null;
            Hnsw graphI8 = null;
            List<Config> configs = new ArrayList<>();
            for (String mode : modes) {
                if (mode.equals("keyword")) {
                    configs.add(new Config("keyword", keyword));
                    continue;
                }
                String[] parts = mode.split("-");   // e.g. hybrid-f32
                boolean int8 = parts[1].equals("i8");
                if (!int8 && graphF32 == null) {
                    graphF32 = VectorFiles.openGraph(vectorDir, VectorFiles.open(vectorDir, index.docs(), "float32"));
                }
                if (int8 && graphI8 == null) {
                    VectorStore v = VectorFiles.open(vectorDir, index.docs(), "int8");
                    graphI8 = VectorFiles.openGraph(vectorDir, v);
                }
                SmartSearcher.Mode m = parts[0].equals("semantic") ? SmartSearcher.Mode.SEMANTIC : SmartSearcher.Mode.HYBRID;
                configs.add(new Config(mode, new SmartSearcher(keyword, embedder,
                        new HnswIndex("MiniLM-200", int8 ? graphI8 : graphF32, ef, index.docs().size()), index.docs(), m, 1)));
            }

            List<List<Evaluator.Outcome>> outcomes = new ArrayList<>();
            List<double[]> medianMs = new ArrayList<>();
            List<double[]> allMs = new ArrayList<>();
            for (Config c : configs) {
                outcomes.add(new Evaluator(index.docs()).run(c.ranker(), queries));
                double[][] t = time(c.ranker(), queries);
                medianMs.add(t[0]);
                allMs.add(t[1]);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("# Smart search eval: keyword vs semantic vs hybrid (RRF)\n").append(RunInfo.header());
            sb.append(String.format(Locale.US, "# queries: %s (%d queries)%n", queriesPath, queries.size()));
            sb.append("# semantic/hybrid rank only their own lists (semantic: top 100; hybrid: union of keyword top 100"
                    + " and semantic top 100). An article outside them = not found (\"-\").\n");
            sb.append("# speed = whole query incl. embedding, single thread, median of " + REPEAT
                    + " runs per query after a warm-up\n");
            sb.append("# p95 over all runs (queries x repeats):");
            for (int i = 0; i < configs.size(); i++) {
                double[] all = allMs.get(i);
                sb.append(String.format(Locale.US, " %s %.2f ms;", configs.get(i).name(),
                        all[(int) Math.ceil(all.length * 0.95) - 1]));
            }
            sb.append("\n\n");
            sb.append(EvalReport.comparison(configs, outcomes, medianMs));
            Path out = Path.of("eval/results");
            Files.writeString(out.resolve(label + ".txt"), sb.toString());
            Files.writeString(out.resolve(label + ".tsv"), EvalReport.tsv(configs, outcomes, medianMs));
            System.out.print(sb);
        }
    }

    /** [0] = median per query, [1] = every single run sorted (for p95). */
    private static double[][] time(QueryRanker ranker, List<EvalQuery> queries) {
        for (EvalQuery q : queries) {
            ranker.search(q.query(), 10);               // warm-up
        }
        double[] medians = new double[queries.size()];
        double[] all = new double[queries.size() * REPEAT];
        int n = 0;
        for (int i = 0; i < queries.size(); i++) {
            double[] runs = new double[REPEAT];
            for (int r = 0; r < REPEAT; r++) {
                long s = System.nanoTime();
                ranker.search(queries.get(i).query(), 10);
                runs[r] = (System.nanoTime() - s) / 1e6;
                all[n++] = runs[r];
            }
            Arrays.sort(runs);
            medians[i] = runs[REPEAT / 2];
        }
        Arrays.sort(all);
        return new double[][] {medians, all};
    }
}
