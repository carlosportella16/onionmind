package com.onionmind.ai;

import org.springframework.stereotype.Component;

@Component
public class NoOpAIOrchestrator implements AIOrchestrator {
    public Summary summarize(String text, TaskContext ctx) {
        throw new UnsupportedOperationException("IA chega na Fase 3 — ver SDD seção 4.2");
    }
    public Embedding embed(String text, TaskContext ctx) {
        throw new UnsupportedOperationException("IA chega na Fase 3 — ver SDD seção 4.2");
    }
}
