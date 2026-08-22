package com.onionmind.ingestion;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.onionmind.content.Document;
import com.onionmind.content.DocumentType;

import java.time.Instant;

public record RawPageEvent(
    String url,
    @JsonProperty("source_type") String sourceType,
    String html,
    @JsonProperty("fetched_at") Instant fetchedAt
) {
    public Document toDocument() {
        return new Document(url, sourceType, html, null, DocumentType.HTML, fetchedAt);
    }
}
