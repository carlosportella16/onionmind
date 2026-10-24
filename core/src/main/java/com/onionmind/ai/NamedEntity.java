package com.onionmind.ai;

import java.util.Set;

/** One named entity the model found: {@code type} is one of {@link #TYPES}, {@code value} as written. */
public record NamedEntity(String type, String value, double confidence) {

    /** The only types extractEntities ever returns — anything else the model says is dropped. */
    public static final Set<String> TYPES = Set.of("person", "organization", "location", "technology");

    /** Longer than this is not a name, it's the model echoing content back. */
    public static final int MAX_VALUE_LENGTH = 200;
}
