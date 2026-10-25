package com.onionmind.ai;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against the real model (local-onnx-embeddings D8): the model is deterministic, a fake would hide quality. */
class OnnxEmbedderTest {

    private static final OnnxEmbedder EMBEDDER = TestEmbeddingModel.SHARED;
    // Onion links tokenize densely: ~4 000 chars of them is well past 510 tokens.
    private static final String LONG = String.join(" ", Collections.nCopies(70,
        "http://abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion/"));

    @Test
    void vectorsHaveTheConfiguredDimensionAndUnitNorm() {
        float[] v = embed("Welcome to the market, orders ship within 48 hours.", false);

        assertThat(v).hasSize(384);
        assertThat(norm(v)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void sameTextSameRoleSameVector() {
        String text = "Our email provider keeps no logs and works over Tor.";

        assertThat(embed(text, false)).isEqualTo(embed(text, false));
        assertThat(embed(text, true)).isNotEqualTo(embed(text, false));
    }

    @Test
    void portugueseQueryIsClosestToTheEnglishPassageOnTheSameTopic() {
        float[] query = embed("como receber pagamentos em bitcoin de forma anônima", true);
        float[] onTopic = embed("Accept bitcoin payments anonymously: we generate a fresh wallet address "
            + "for every order and never ask for your identity.", false);
        float[] offTopic = embed("Slow-cooked beef stew with carrots, onions and red wine, served with "
            + "mashed potatoes.", false);

        assertThat(dot(query, onTopic)).isGreaterThan(dot(query, offTopic));
    }

    @Test
    void chunkPastTheTokenLimitIsCutAndCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OnnxEmbedder embedder = new OnnxEmbedder(TestEmbeddingModel.DIR, 1, 384, true, registry);
        try {
            embedder.embed(List.of("short text", LONG), false);

            assertThat(registry.get("embedding.truncated.chunks.total").counter().count()).isEqualTo(1.0);
        } finally {
            close(embedder);
        }
    }

    @Test
    void queryWaitsForAtMostOneChunkOfALongEmbedAll() throws Exception {
        List<String> page = Collections.nCopies(40, LONG);
        CompletableFuture<List<Embedding>> backfill = CompletableFuture.supplyAsync(() -> EMBEDDER.embed(page, false));
        while (!EMBEDDER.lock.isLocked()) {
            Thread.onSpinWait();
        }

        float[] query = embed("public monero node", true);

        assertThat(query).hasSize(384);
        assertThat(backfill).isNotDone();
        assertThat(backfill.get(2, TimeUnit.MINUTES)).hasSize(40);
    }

    @Test
    void wrongConfiguredDimensionFails() {
        OnnxEmbedder embedder = new OnnxEmbedder(TestEmbeddingModel.DIR, 1, 768, true, new SimpleMeterRegistry());
        try {
            assertThatThrownBy(() -> embedder.embed(List.of("x"), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("384-dimension").hasMessageContaining("768");
        } finally {
            close(embedder);
        }
    }

    @Test
    void bootFailsNamingTheMissingFileWhenEmbeddingsAreOn(@TempDir Path empty) {
        runner(empty).withPropertyValues("embedding.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .rootCause().hasMessageContaining(empty.resolve(OnnxEmbedder.MODEL_FILE).toString());
        });
    }

    @Test
    void bootDoesNotLookForTheModelWhenEmbeddingsAreOff(@TempDir Path empty) {
        runner(empty).withPropertyValues("embedding.enabled=false").run(context ->
            assertThat(context).hasNotFailed().hasSingleBean(OnnxEmbedder.class));
    }

    private static ApplicationContextRunner runner(Path modelDir) {
        return new ApplicationContextRunner()
            .withBean(SimpleMeterRegistry.class)
            .withUserConfiguration(OnnxEmbedder.class)
            .withPropertyValues("embedding.model-dir=" + modelDir);
    }

    private static float[] embed(String text, boolean query) {
        return EMBEDDER.embed(List.of(text), query).getFirst().vector();
    }

    private static void close(OnnxEmbedder embedder) {
        try {
            embedder.close();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static double norm(float[] v) {
        return Math.sqrt(dot(v, v));
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
