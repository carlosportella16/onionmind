package com.onionmind.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "embedding", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoOpAIOrchestrator implements AIOrchestrator {
    public Summary summarize(String text, TaskContext ctx) {
        throw new UnsupportedOperationException("IA chega na Fase 3 — ver SDD seção 4.2");
    }
    public Embedding embed(String text, TaskContext ctx) {
        throw new UnsupportedOperationException("IA chega na Fase 3 — ver SDD seção 4.2");
    }
}
