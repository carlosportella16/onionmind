package com.onionmind.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoOpAIOrchestratorTest {

    private final NoOpAIOrchestrator orchestrator = new NoOpAIOrchestrator();

    private TaskContext context() {
        return new TaskContext(TaskContext.TaskType.SUMMARIZE, 100, "en", false, false, null, 0);
    }

    @Test
    void summarizeThrowsUnsupportedOperationException() {
        assertThatThrownBy(() -> orchestrator.summarize("text", context()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void embedThrowsUnsupportedOperationException() {
        assertThatThrownBy(() -> orchestrator.embed("text", context()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void classifyThrowsUnsupportedOperationException() {
        assertThatThrownBy(() -> orchestrator.classify("text", context()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void translateThrowsUnsupportedOperationException() {
        assertThatThrownBy(() -> orchestrator.translate("text", "pt", context()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void detectLanguageThrowsUnsupportedOperationException() {
        assertThatThrownBy(() -> orchestrator.detectLanguage("text", context()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
