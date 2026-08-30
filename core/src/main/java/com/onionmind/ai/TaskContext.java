package com.onionmind.ai;

public record TaskContext(
    TaskType type, int approxTokens, String sourceLanguage,
    boolean critical, boolean interactive, Double previousConfidence, int attemptNumber
) {
    public enum TaskType { SUMMARIZE, CLASSIFY, TRANSLATE, EXTRACT_ENTITIES, EMBED }

    /** Batch (non-interactive) context — the only kind Fase 3 produces. */
    public static TaskContext batch(TaskType type, int approxTokens, String sourceLanguage, boolean critical) {
        return new TaskContext(type, approxTokens, sourceLanguage, critical, false, null, 0);
    }

    public TaskContext withAttempt(int attemptNumber) {
        return new TaskContext(type, approxTokens, sourceLanguage, critical, interactive, previousConfidence, attemptNumber);
    }

    public TaskContext withPreviousConfidence(double confidence) {
        return new TaskContext(type, approxTokens, sourceLanguage, critical, interactive, confidence, attemptNumber);
    }
}
