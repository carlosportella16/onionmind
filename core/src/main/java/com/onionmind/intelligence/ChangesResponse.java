package com.onionmind.intelligence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Response of {@code GET /api/changes}. {@code summary} is null while the change is still pending. */
public record ChangesResponse(Instant since, String category, long total, List<Change> changes) {

    public record Change(String url, int fromVersion, int toVersion, Instant detectedAt, String status,
                         ChangeSummary summary, List<Map<String, String>> entitiesAdded,
                         List<Map<String, String>> entitiesRemoved, String categoryFrom, String categoryTo) {}

    public record ChangeSummary(String text, double confidence) {}
}
