package com.onionmind.ai.decorator;

import com.onionmind.ai.Entity;
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

    @Test
    void parsesWellFormedEntityArray() {
        ValidatedEntities r = validator.validateEntities(new CompletionResponse(
            "[{\"type\":\"CRYPTO_WALLET\",\"value\":\"1A2b3C\",\"confidence\":0.9},"
                + "{\"type\":\"ORGANIZATION\",\"value\":\"Acme Corp\",\"confidence\":0.8}]"));

        assertThat(r.usable()).isTrue();
        assertThat(r.entities()).containsExactly(
            new Entity(Entity.EntityType.CRYPTO_WALLET, "1A2b3C", 0.9),
            new Entity(Entity.EntityType.ORGANIZATION, "Acme Corp", 0.8));
        assertThat(r.confidence()).isCloseTo(0.85, org.assertj.core.data.Offset.offset(0.0001)); // average of the two
    }

    @Test
    void emptyArrayIsAConfidentNoEntitiesResult() {
        ValidatedEntities r = validator.validateEntities(new CompletionResponse("[]"));

        assertThat(r.usable()).isTrue();
        assertThat(r.entities()).isEmpty();
        assertThat(r.confidence()).isEqualTo(1.0);
    }

    @Test
    void toleratesEntityArrayWrappedInProseOrFences() {
        ValidatedEntities r = validator.validateEntities(new CompletionResponse(
            "Aqui estão as entidades:\n```json\n[{\"type\":\"PERSON\",\"value\":\"Ana\",\"confidence\":0.7}]\n```"));

        assertThat(r.entities()).containsExactly(new Entity(Entity.EntityType.PERSON, "Ana", 0.7));
    }

    @Test
    void skipsMalformedEntriesButKeepsTheRest() {
        ValidatedEntities r = validator.validateEntities(new CompletionResponse("""
            [{"type":"NOT_A_REAL_TYPE","value":"x","confidence":0.9},
             {"type":"PERSON","confidence":0.9},
             {"type":"PERSON","value":"","confidence":0.9},
             {"type":"LOCATION","value":"Berlin","confidence":0.6}]
            """));

        assertThat(r.entities()).containsExactly(new Entity(Entity.EntityType.LOCATION, "Berlin", 0.6));
    }

    @Test
    void nonArrayResponseIsInvalid() {
        ValidatedEntities r = validator.validateEntities(new CompletionResponse("not an array at all"));

        assertThat(r.usable()).isFalse();
        assertThat(r.entities()).isEmpty();
    }

    @Test
    void entityConfidenceIsClampedToUnitRange() {
        ValidatedEntities r = validator.validateEntities(new CompletionResponse(
            "[{\"type\":\"PERSON\",\"value\":\"Ana\",\"confidence\":5}]"));

        assertThat(r.entities().getFirst().confidence()).isEqualTo(1.0);
    }
}
