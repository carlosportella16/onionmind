package com.onionmind.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskContextTest {

    @Test
    void accessorsReturnConstructorValues() {
        TaskContext ctx = new TaskContext(TaskContext.TaskType.TRANSLATE, 250, "pt", true, false, 0.8, 2);

        assertThat(ctx.type()).isEqualTo(TaskContext.TaskType.TRANSLATE);
        assertThat(ctx.approxTokens()).isEqualTo(250);
        assertThat(ctx.sourceLanguage()).isEqualTo("pt");
        assertThat(ctx.critical()).isTrue();
        assertThat(ctx.interactive()).isFalse();
        assertThat(ctx.previousConfidence()).isEqualTo(0.8);
        assertThat(ctx.attemptNumber()).isEqualTo(2);
    }

    @Test
    void allTaskTypesExist() {
        assertThat(TaskContext.TaskType.values()).containsExactly(
                TaskContext.TaskType.SUMMARIZE,
                TaskContext.TaskType.CLASSIFY,
                TaskContext.TaskType.TRANSLATE,
                TaskContext.TaskType.EXTRACT_ENTITIES,
                TaskContext.TaskType.EMBED
        );
    }
}
