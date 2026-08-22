package com.onionmind.ai;

public interface AIOrchestrator {
    Summary summarize(String text, TaskContext ctx);
    Embedding embed(String text, TaskContext ctx);
}
