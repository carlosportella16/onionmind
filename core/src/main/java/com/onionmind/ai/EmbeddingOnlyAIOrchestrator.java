package com.onionmind.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Embeddings only, no generation. Active when {@code embedding.enabled=true} and
 * {@code ai.enabled} is not on — once {@code ai.enabled=true}, DefaultAIOrchestrator takes
 * over (and still serves {@code embed()} via the same {@link GeminiEmbedder}).
 */
@Component
@ConditionalOnExpression("!${ai.enabled:false} and ${embedding.enabled:false}")
public class EmbeddingOnlyAIOrchestrator implements AIOrchestrator {

    private static final String MESSAGE = "Geração por IA está desligada — ligue ai.enabled";

    private final GeminiEmbedder embedder;

    EmbeddingOnlyAIOrchestrator(GeminiEmbedder embedder) {
        this.embedder = embedder;
    }

    @Override
    public Summary summarize(String text, TaskContext ctx) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public Classification classify(String text, TaskContext ctx) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public Translation translate(String text, String targetLang, TaskContext ctx) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public LanguageDetection detectLanguage(String text, TaskContext ctx) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public EntityExtraction extractEntities(String text, TaskContext ctx) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public Embedding embed(String text, TaskContext ctx) {
        return embedder.embed(List.of(text), null, ctx.interactive()).getFirst();
    }

    @Override
    public List<Embedding> embedAll(List<String> texts, String title, TaskContext ctx) {
        return embedder.embed(texts, title, ctx.interactive());
    }
}
