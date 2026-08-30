package com.onionmind.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Fase 2 wiring: embeddings only, no generation. Active when {@code embedding.enabled=true}
 * and {@code ai.enabled} is not on — once {@code ai.enabled=true}, DefaultAIOrchestrator
 * takes over (and still serves {@code embed()} via the same {@link OllamaEmbedder}).
 */
@Component
@ConditionalOnExpression("!${ai.enabled:false} and ${embedding.enabled:false}")
public class OllamaAIOrchestrator implements AIOrchestrator {

    private final OllamaEmbedder embedder;

    public OllamaAIOrchestrator(@Value("${ollama.url}") String url,
                                @Value("${ollama.model}") String model) {
        this.embedder = new OllamaEmbedder(url, model);
    }

    @Override
    public Summary summarize(String text, TaskContext ctx) {
        throw new UnsupportedOperationException("Sumarização chega na Fase 3 — ligue ai.enabled");
    }

    @Override
    public Classification classify(String text, TaskContext ctx) {
        throw new UnsupportedOperationException("Classificação chega na Fase 3 — ligue ai.enabled");
    }

    @Override
    public Translation translate(String text, String targetLang, TaskContext ctx) {
        throw new UnsupportedOperationException("Tradução chega na Fase 3 — ligue ai.enabled");
    }

    @Override
    public Embedding embed(String text, TaskContext ctx) {
        return embedder.embed(text);
    }
}
