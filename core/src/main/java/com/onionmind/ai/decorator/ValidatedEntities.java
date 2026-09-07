package com.onionmind.ai.decorator;

import com.onionmind.ai.Entity;

import java.util.List;

/**
 * Entities need their own result shape — {@link ValidatedResult}'s primary/secondary strings
 * don't fit a list of typed values. {@code parsedOk} distinguishes "parsed fine, zero entities
 * found" (a legitimate, confident result) from "the response wasn't a JSON array at all".
 */
public record ValidatedEntities(List<Entity> entities, double confidence, boolean parsedOk) {

    public static ValidatedEntities invalid() {
        return new ValidatedEntities(List.of(), 0.0, false);
    }

    public boolean usable() {
        return parsedOk;
    }
}
