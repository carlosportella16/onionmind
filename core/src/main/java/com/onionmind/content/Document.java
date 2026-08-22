package com.onionmind.content;

import java.time.Instant;

public record Document(
    String url, String sourceType, String rawHtml, String extractedText, DocumentType type, Instant fetchedAt
) {
    public Document withExtractedText(String text) {
        return new Document(url, sourceType, rawHtml, text, type, fetchedAt);
    }
}
