package com.onionmind.ai.decorator;

import com.onionmind.ai.TaskContext.TaskType;
import com.onionmind.ai.provider.CompletionResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationDecoratorTest {

    private final ValidationDecorator validator = new ValidationDecorator();

    @Test
    void parsesWellFormedSummary() {
        ValidatedResult r = validator.validate(
            new CompletionResponse("{\"summary\":\"um resumo\",\"confidence\":0.88}"), TaskType.SUMMARIZE);

        assertThat(r.primary()).isEqualTo("um resumo");
        assertThat(r.confidence()).isEqualTo(0.88);
        assertThat(r.usable()).isTrue();
    }

    @Test
    void parsesTranslationWithDetectedLanguage() {
        ValidatedResult r = validator.validate(new CompletionResponse(
            "{\"translation\":\"olá\",\"detectedLanguage\":\"en\",\"confidence\":0.9}"), TaskType.TRANSLATE);

        assertThat(r.primary()).isEqualTo("olá");
        assertThat(r.secondary()).isEqualTo("en");
    }

    @Test
    void toleratesJsonWrappedInProseOrFences() {
        ValidatedResult r = validator.validate(new CompletionResponse(
            "Claro! ```json\n{\"category\":\"forum\",\"confidence\":0.7}\n``` espero ter ajudado"),
            TaskType.CLASSIFY);

        assertThat(r.primary()).isEqualTo("forum");
        assertThat(r.confidence()).isEqualTo(0.7);
    }

    @Test
    void malformedJsonIsConfidenceZero() {
        ValidatedResult r = validator.validate(new CompletionResponse("not json at all"), TaskType.SUMMARIZE);

        assertThat(r.confidence()).isZero();
        assertThat(r.usable()).isFalse();
    }

    @Test
    void missingPrimaryFieldIsInvalid() {
        ValidatedResult r = validator.validate(
            new CompletionResponse("{\"confidence\":0.99}"), TaskType.SUMMARIZE);

        assertThat(r.usable()).isFalse();
        assertThat(r.confidence()).isZero();
    }

    @Test
    void confidenceIsClampedToUnitRange() {
        assertThat(validator.validate(
            new CompletionResponse("{\"summary\":\"x\",\"confidence\":5}"), TaskType.SUMMARIZE).confidence())
            .isEqualTo(1.0);
        assertThat(validator.validate(
            new CompletionResponse("{\"summary\":\"x\",\"confidence\":-2}"), TaskType.SUMMARIZE).confidence())
            .isZero();
    }
}
