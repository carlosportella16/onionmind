package com.onionmind.ai;

public record TaskContext(
    TaskType type, int approxTokens, String sourceLanguage,
    boolean critical, boolean interactive, Double previousConfidence, int attemptNumber
) {
    public enum TaskType { SUMMARIZE, CLASSIFY, TRANSLATE, EXTRACT_ENTITIES, EMBED }
}
