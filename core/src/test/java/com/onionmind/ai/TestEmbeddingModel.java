package com.onionmind.ai;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.nio.file.Path;

/**
 * The real local model, from the same default directory as application.yml (CI fills it with
 * scripts/fetch-embedding-model.sh). One shared embedder, so tests load the session once.
 */
final class TestEmbeddingModel {

    static final Path DIR = Path.of(System.getenv().getOrDefault("EMBEDDING_MODEL_DIR",
        System.getProperty("user.home") + "/.cache/onionmind/models/multilingual-e5-small"));
    static final OnnxEmbedder SHARED = new OnnxEmbedder(DIR, 2, 384, true, new SimpleMeterRegistry());

    private TestEmbeddingModel() {
    }
}
