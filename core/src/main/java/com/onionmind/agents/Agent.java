package com.onionmind.agents;

import com.onionmind.content.Categories;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A saved agent (agents/agent-config) — also the request body of {@code POST/PUT /api/agents},
 * where {@code id} and the timestamps are ignored. Boxed numbers so a missing field is a
 * validation error naming it, not a parse error.
 */
record Agent(Long id, String name, String instructions, String topic, List<String> categories,
             Integer windowDays, List<String> tools, String costProfile, Integer maxTokensPerRun,
             Boolean daily, Instant createdAt, Instant updatedAt) {

    /** The fixed tool set, in the order the budget cut keeps them (design D3). */
    static final List<String> TOOLS = List.of("search", "changes", "entities");
    static final List<String> COST_PROFILES = List.of("interactive", "batch");
    static final int MAX_NAME_CHARS = 100;
    static final int MAX_TOPIC_CHARS = 200;
    // /api/changes reaches 30 days back at most.
    static final int MAX_WINDOW_DAYS = 30;
    static final int MAX_TOKENS_PER_RUN = 100_000;

    boolean interactive() {
        return "interactive".equals(costProfile);
    }

    /** Trimmed, nulls as defaults, duplicates dropped — what gets validated and saved. */
    Agent normalized() {
        return new Agent(id, strip(name), instructions == null ? "" : instructions.strip(), strip(topic),
            distinct(categories), windowDays, distinct(tools), costProfile, maxTokensPerRun,
            Boolean.TRUE.equals(daily), createdAt, updatedAt);
    }

    /** Field → message; empty when valid. Call on {@link #normalized()}. */
    Map<String, String> errors(int maxInstructionsChars) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (name.isEmpty() || name.length() > MAX_NAME_CHARS) {
            errors.put("name", "name is required, up to " + MAX_NAME_CHARS + " characters");
        }
        if (instructions.length() > maxInstructionsChars) {
            errors.put("instructions", "instructions must be up to " + maxInstructionsChars + " characters");
        }
        if (topic.isEmpty() || topic.length() > MAX_TOPIC_CHARS) {
            errors.put("topic", "topic is required, up to " + MAX_TOPIC_CHARS + " characters");
        }
        categories.stream().filter(c -> !Categories.TAXONOMY.contains(c)).findFirst()
            .ifPresent(c -> errors.put("categories", "unknown category: " + c));
        if (windowDays == null || windowDays < 1 || windowDays > MAX_WINDOW_DAYS) {
            errors.put("windowDays", "windowDays must be between 1 and " + MAX_WINDOW_DAYS);
        }
        if (tools.isEmpty()) {
            errors.put("tools", "at least one tool is required: " + String.join(", ", TOOLS));
        }
        tools.stream().filter(t -> !TOOLS.contains(t)).findFirst()
            .ifPresent(t -> errors.put("tools", "unknown tool: " + t));
        if (costProfile == null || !COST_PROFILES.contains(costProfile)) {
            errors.put("costProfile", "costProfile must be interactive or batch");
        }
        if (maxTokensPerRun == null || maxTokensPerRun < 1 || maxTokensPerRun > MAX_TOKENS_PER_RUN) {
            errors.put("maxTokensPerRun", "maxTokensPerRun must be between 1 and " + MAX_TOKENS_PER_RUN);
        }
        return errors;
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }

    private static List<String> distinct(List<String> values) {
        return values == null ? List.of()
            : values.stream().filter(Objects::nonNull).map(String::strip).distinct().toList();
    }
}
