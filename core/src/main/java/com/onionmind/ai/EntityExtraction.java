package com.onionmind.ai;

import java.util.List;

/** Result of {@code extractEntities}: entities at or above the minimum confidence, and the model's overall confidence. */
public record EntityExtraction(List<NamedEntity> entities, double confidence) {
}
