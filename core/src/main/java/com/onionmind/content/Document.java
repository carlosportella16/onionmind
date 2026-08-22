package com.onionmind.content;

public record Document(
    String url, String sourceType, String rawHtml, String extractedText, DocumentType type
) {
    public Document withExtractedText(String text) {
        return new Document(url, sourceType, rawHtml, text, type);
    }
}
