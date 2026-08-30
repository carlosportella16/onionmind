package com.onionmind.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Neither embeddings nor generation are enabled — every call fails loudly. Active when
 * both {@code ai.enabled} and {@code embedding.enabled} are off (the default).
 */
@Component
@ConditionalOnExpression("!${ai.enabled:false} and !${embedding.enabled:false}")
public class NoOpAIOrchestrator implements AIOrchestrator {

    private static final String MESSAGE = "IA não está habilitada — ligue ai.enabled / embedding.enabled";

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
    public Embedding embed(String text, TaskContext ctx) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}
