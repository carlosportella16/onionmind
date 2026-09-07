package com.onionmind.ai.decorator;

import com.onionmind.ai.Entity;
import com.onionmind.ai.TaskContext.TaskType;
import com.onionmind.ai.provider.CompletionResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

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
            case DETECT_LANGUAGE -> extract(json, "language", null);
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

    /**
     * Parses the array a {@code extractEntities} completion is expected to return. An empty
     * array is a confident "no entities" result, not a failure — only a response that isn't a
     * JSON array at all comes back {@link ValidatedEntities#invalid()}. Individual malformed
     * entries (missing field, unknown type) are dropped rather than failing the whole batch.
     */
    public ValidatedEntities validateEntities(CompletionResponse response) {
        JsonNode array = parseArray(response.text());
        if (array == null || !array.isArray()) {
            return ValidatedEntities.invalid();
        }

        List<Entity> entities = new ArrayList<>();
        for (JsonNode node : array) {
            String typeName = node.path("type").asString(null);
            String value = node.path("value").asString(null);
            if (typeName == null || value == null || value.isBlank()) {
                continue;
            }
            Entity.EntityType type;
            try {
                type = Entity.EntityType.valueOf(typeName);
            } catch (IllegalArgumentException e) {
                continue;
            }
            entities.add(new Entity(type, value, clamp(node.path("confidence").asDouble(0.0))));
        }

        double confidence = entities.isEmpty() ? 1.0
            : entities.stream().mapToDouble(Entity::confidence).average().orElse(0.0);
        return new ValidatedEntities(entities, confidence, true);
    }

    /** Tolerates a JSON array wrapped in prose or markdown fences — takes the outermost [...]. */
    private JsonNode parseArray(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return mapper.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
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
