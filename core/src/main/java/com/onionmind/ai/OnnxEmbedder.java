package com.onionmind.ai;

import ai.djl.sentencepiece.SpProcessor;
import ai.djl.sentencepiece.SpTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The one place that makes embeddings: multilingual-e5-small int8 run in-process by ONNX Runtime,
 * tokenized by SentencePiece (local-onnx-embeddings D1/D2). No text leaves the machine and no key
 * is needed. Both DefaultAIOrchestrator and EmbeddingOnlyAIOrchestrator delegate here.
 *
 * <p>e5 wants the role in the text: {@code query: } for searches, {@code passage: } for chunks —
 * the same format on both sides, or queries and chunks stop matching. Mean pooling, then L2 norm.
 * Deterministic, so confidence is always 1.0.
 *
 * <p>One session, created on the first call (contexts that never embed don't pay its ~400 MB),
 * with {@code embedding.threads} intra-op threads. One inference at a time under a fair lock,
 * taken per chunk, so a query waits for at most one chunk of a long page (D4).
 */
@Component
class OnnxEmbedder {

    static final String MODEL_FILE = "model_quantized.onnx";
    static final String TOKENIZER_FILE = "sentencepiece.bpe.model";
    // 512 positions minus <s> and </s> (D5).
    static final int MAX_TOKENS = 510;
    // XLM-R ids (fairseq): SentencePiece id + 1, with <s>=0, </s>=2 and <unk>=3 for SentencePiece's 0.
    private static final long BOS = 0;
    private static final long EOS = 2;
    private static final long UNK = 3;

    // ponytail: global lock. If semantic search starts waiting too long behind the backfill,
    // split off an interactive session at +~120 MB (D4).
    final ReentrantLock lock = new ReentrantLock(true);
    private final Path modelDir;
    private final int threads;
    private final int dimensions;
    private final Counter truncated;
    private SpProcessor tokenizer;
    private SpTokenizer spTokenizer;
    private OrtSession session;

    OnnxEmbedder(@Value("${embedding.model-dir}") Path modelDir,
                 @Value("${embedding.threads:2}") int threads,
                 @Value("${embedding.dimensions:384}") int dimensions,
                 @Value("${embedding.enabled:false}") boolean enabled,
                 MeterRegistry meterRegistry) {
        this.modelDir = modelDir;
        this.threads = threads;
        this.dimensions = dimensions;
        this.truncated = Counter.builder("embedding.truncated.chunks.total")
            .description("Chunks cut at the model's token limit before embedding")
            .register(meterRegistry);
        if (enabled) {
            for (String file : List.of(MODEL_FILE, TOKENIZER_FILE)) {
                Path path = modelDir.resolve(file);
                if (!Files.isRegularFile(path)) {
                    throw new IllegalStateException("embedding model file not found: " + path
                        + " — run scripts/fetch-embedding-model.sh or point embedding.model-dir at it");
                }
            }
        }
    }

    /** One vector per text, in order. {@code query} marks a search query; otherwise each text is a chunk. */
    List<Embedding> embed(List<String> texts, boolean query) {
        List<Embedding> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            lock.lock();
            try {
                out.add(new Embedding(infer((query ? "query: " : "passage: ") + text), 1.0));
            } finally {
                lock.unlock();
            }
        }
        return out;
    }

    private float[] infer(String text) {
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            if (session == null) {
                spTokenizer = new SpTokenizer(modelDir.resolve(TOKENIZER_FILE));
                tokenizer = spTokenizer.getProcessor();
                OrtSession.SessionOptions options = new OrtSession.SessionOptions();
                options.setIntraOpNumThreads(threads);
                options.setInterOpNumThreads(1);
                session = env.createSession(modelDir.resolve(MODEL_FILE).toString(), options);
            }
            int[] pieces = tokenizer.encode(text);
            if (pieces.length > MAX_TOKENS) {
                truncated.increment();
            }
            int len = Math.min(pieces.length, MAX_TOKENS) + 2;
            long[][] ids = new long[1][len];
            long[][] mask = new long[1][len];
            ids[0][0] = BOS;
            ids[0][len - 1] = EOS;
            for (int i = 1; i < len - 1; i++) {
                int piece = pieces[i - 1];
                ids[0][i] = piece == 0 ? UNK : piece + 1;
            }
            Arrays.fill(mask[0], 1);
            Map<String, OnnxTensor> inputs = new HashMap<>();
            try {
                inputs.put("input_ids", OnnxTensor.createTensor(env, ids));
                inputs.put("attention_mask", OnnxTensor.createTensor(env, mask));
                if (session.getInputNames().contains("token_type_ids")) {
                    inputs.put("token_type_ids", OnnxTensor.createTensor(env, new long[1][len]));
                }
                try (OrtSession.Result result = session.run(inputs)) {
                    return meanPoolNormalized(((float[][][]) result.get(0).getValue())[0]);
                }
            } finally {
                inputs.values().forEach(OnnxTensor::close);
            }
        } catch (OrtException e) {
            throw new IllegalStateException("local embedding inference failed", e);
        } catch (IOException e) {
            throw new UncheckedIOException("could not load the embedding tokenizer from " + modelDir, e);
        }
    }

    private float[] meanPoolNormalized(float[][] hidden) {
        float[] out = new float[hidden[0].length];
        if (out.length != dimensions) {
            throw new IllegalStateException("embedding model makes " + out.length
                + "-dimension vectors but embedding.dimensions is " + dimensions);
        }
        for (float[] token : hidden) {
            for (int d = 0; d < out.length; d++) {
                out[d] += token[d];
            }
        }
        double norm = 0;
        for (float v : out) {
            norm += v * v;
        }
        float scale = (float) (1 / Math.sqrt(norm)); // the mean's 1/len cancels out in the norm
        for (int d = 0; d < out.length; d++) {
            out[d] *= scale;
        }
        return out;
    }

    @PreDestroy
    void close() throws OrtException {
        lock.lock();
        try {
            if (session != null) {
                session.close();
                spTokenizer.close();
                session = null;
            }
        } finally {
            lock.unlock();
        }
    }
}
