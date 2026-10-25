package com.onionmind.ai.decorator;

import com.onionmind.ai.NamedEntity;
import com.onionmind.ai.TaskContext.TaskType;
import com.onionmind.ai.provider.CompletionResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
            case EXTRACT_ENTITIES -> extractEntities(json);
            case REWRITE_QUERY -> extract(json, "query", null);
            case ANSWER -> extractCited(json, "answer");
            case REPORT -> extractCited(json, "report");
            case EMBED -> ValidatedResult.invalid();
        };
    }

    /**
     * {@code {"answer"|"report": "...", "citations": ["S1", ...], "confidence": x}}. A blank text or
     * citations that aren't a list of strings is invalid — prompt-injected prose never gets
     * through. The citations travel as a JSON array in {@code secondary}; whether each id was
     * actually sent is the orchestrator's check.
     */
    private ValidatedResult extractCited(JsonNode json, String textField) {
        JsonNode answer = json.get(textField);
        JsonNode citations = json.get("citations");
        if (answer == null || !answer.isString() || answer.asString().isBlank()
                || citations == null || !citations.isArray()) {
            return ValidatedResult.invalid();
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode c : citations) {
            if (!c.isString()) {
                return ValidatedResult.invalid();
            }
            ids.add(c.asString());
        }
        return new ValidatedResult(answer.asString(), mapper.writeValueAsString(ids),
            clamp(json.path("confidence").asDouble(0.0)));
    }

    /**
     * {@code {"entities": [{type, value, confidence}], "confidence": x}}. A missing or non-array
     * {@code entities} is invalid; inside it, types outside the taxonomy, blank or oversized
     * values are dropped (prompt-injected output can't smuggle anything past this). The kept
     * entities travel as a JSON array in {@code primary} — {@code "[]"} is a valid answer.
     */
    private ValidatedResult extractEntities(JsonNode json) {
        JsonNode entities = json.get("entities");
        if (entities == null || !entities.isArray()) {
            return ValidatedResult.invalid();
        }
        List<NamedEntity> kept = new ArrayList<>();
        for (JsonNode e : entities) {
            String type = e.path("type").asString("").trim().toLowerCase(Locale.ROOT);
            String value = e.path("value").asString("").strip();
            if (NamedEntity.TYPES.contains(type) && !value.isEmpty() && value.length() <= NamedEntity.MAX_VALUE_LENGTH) {
                kept.add(new NamedEntity(type, value, clamp(e.path("confidence").asDouble(0.0))));
            }
        }
        return new ValidatedResult(mapper.writeValueAsString(kept), null,
            clamp(json.path("confidence").asDouble(0.0)));
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
