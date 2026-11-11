package dev.sift.engine.embed;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Turns a query into a 384-number meaning vector, the same way the articles were embedded in Python:
 * our WordPiece tokenizer → the model (run by ONNX Runtime) → our pooling + normalising.
 *
 * <p>Two models are supported, described by {@code model-config.json}: all-MiniLM-L6-v2 (pooling = mean of
 * all word pieces, no prefix) and bge-small-en-v1.5 (pooling = the [CLS] vector, and every query gets the
 * instruction "Represent this sentence for searching relevant passages: " in front).
 *
 * <p>Thread-safe: ONNX Runtime allows many threads to call {@code run} on one session at the same time.
 */
public final class QueryEmbedder implements Embedder, AutoCloseable {

    private final OrtEnvironment env;
    private final OrtSession session;
    private final WordPieceTokenizer tokenizer;
    private final int maxLength;
    private final int dims;
    private final String model;
    private final boolean clsPooling;
    private final String queryPrefix;

    private QueryEmbedder(OrtEnvironment env, OrtSession session, WordPieceTokenizer tokenizer, int maxLength,
                          int dims, String model, boolean clsPooling, String queryPrefix) {
        this.env = env;
        this.session = session;
        this.tokenizer = tokenizer;
        this.maxLength = maxLength;
        this.dims = dims;
        this.model = model;
        this.clsPooling = clsPooling;
        this.queryPrefix = queryPrefix;
    }

    /** Opens {@code model.onnx}, {@code vocab.txt} and {@code model-config.json} from the folder. */
    public static QueryEmbedder open(Path modelDir) throws IOException {
        for (String f : new String[] {"model.onnx", "vocab.txt", "model-config.json"}) {
            if (!Files.isReadable(modelDir.resolve(f))) {
                throw new IOException("missing " + modelDir.resolve(f)
                        + " — run data-prep/export_model.py first (see PROGRESS.md)");
            }
        }
        JsonNode config = new ObjectMapper().readTree(modelDir.resolve("model-config.json").toFile());
        WordPieceTokenizer tokenizer = WordPieceTokenizer.load(modelDir.resolve("vocab.txt"));
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            // One thread per query: a short query is too small to gain from splitting, and the server
            // answers many queries at once on separate threads anyway.
            options.setIntraOpNumThreads(1);
            OrtSession session = env.createSession(modelDir.resolve("model.onnx").toString(), options);
            String pooling = config.get("pooling").asText();
            if (!pooling.equals("mean") && !pooling.equals("cls")) {
                throw new IOException("model-config.json: pooling must be 'mean' or 'cls', got '" + pooling + "'");
            }
            String prefix = config.has("query_prefix") ? config.get("query_prefix").asText() : "";
            return new QueryEmbedder(env, session, tokenizer, config.get("max_length").asInt(),
                    config.get("dimensions").asInt(), config.get("model").asText(), pooling.equals("cls"), prefix);
        } catch (OrtException e) {
            throw new IOException("cannot load the ONNX model: " + e.getMessage(), e);
        }
    }

    public int dimensions() {
        return dims;
    }

    public String model() {
        return model;
    }

    public WordPieceTokenizer tokenizer() {
        return tokenizer;
    }

    /** The text put in front of every query ("" for MiniLM). */
    public String queryPrefix() {
        return queryPrefix;
    }

    /** The length-1 meaning vector of the query {@code text} (with the model's query prefix, if any). */
    @Override
    public float[] embed(String text) {
        int[] ids = tokenizer.encode(queryPrefix + text, maxLength);
        long[][] inputIds = new long[1][ids.length];
        long[][] mask = new long[1][ids.length];
        long[][] typeIds = new long[1][ids.length];   // all 0: there is only one sentence
        for (int i = 0; i < ids.length; i++) {
            inputIds[0][i] = ids[i];
            mask[0][i] = 1;
        }
        try (OnnxTensor a = OnnxTensor.createTensor(env, inputIds);
             OnnxTensor b = OnnxTensor.createTensor(env, mask);
             OnnxTensor c = OnnxTensor.createTensor(env, typeIds);
             OrtSession.Result result = session.run(Map.of("input_ids", a, "attention_mask", b, "token_type_ids", c))) {
            float[][][] tokenVectors = (float[][][]) result.get(0).getValue();
            return clsPooling ? VectorMath.clsNormalise(tokenVectors[0])
                    : VectorMath.meanPoolNormalise(tokenVectors[0], mask[0]);
        } catch (OrtException e) {
            throw new IllegalStateException("embedding failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            throw new IllegalStateException(e);
        }
    }
}
