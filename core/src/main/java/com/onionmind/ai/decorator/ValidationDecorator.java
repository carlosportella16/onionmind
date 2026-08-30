package com.onionmind.ai.decorator;

import com.onionmind.ai.TaskContext.TaskType;
import com.onionmind.ai.provider.CompletionResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses the provider's raw text into a {@link ValidatedResult} for the task type.
 * Malformed or structurally-wrong JSON is confidence 0 — that feeds the same escalation
 * loop, no separate retry system (SDD sec. 7.4).
 */
@Component
@ConditionalOnExpression("${ai.enabled:false}")
public class ValidationDecorator {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    public ValidatedResult validate(CompletionResponse response, TaskType type) {
        JsonNode json = parse(response.text());
        if (json == null || !json.isObject()) {
            return ValidatedResult.invalid();
        }

        return switch (type) {
            case SUMMARIZE -> extract(json, "summary", null);
            case CLASSIFY -> extract(json, "category", null);
            case TRANSLATE -> extract(json, "translation", "detectedLanguage");
            case EXTRACT_ENTITIES, EMBED -> ValidatedResult.invalid();
        };
    }

    private ValidatedResult extract(JsonNode json, String primaryField, String secondaryField) {
        JsonNode primary = json.get(primaryField);
        if (primary == null || !primary.isString() || primary.asString().isBlank()) {
            return ValidatedResult.invalid();
        }
        String secondary = secondaryField == null ? null : json.path(secondaryField).asString(null);
        double confidence = clamp(json.path("confidence").asDouble(0.0));
        return new ValidatedResult(primary.asString(), secondary, confidence);
    }

    /** Tolerates a JSON object wrapped in prose or markdown fences — takes the outermost {...}. */
    private JsonNode parse(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return mapper.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
