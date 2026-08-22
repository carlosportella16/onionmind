package com.onionmind.content;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

public record Document(
    String url, String sourceType, String rawHtml, String extractedText,
    DocumentType type, Instant fetchedAt, String contentHash
) {
    public Document(String url, String sourceType, String rawHtml, String extractedText, DocumentType type, Instant fetchedAt) {
        this(url, sourceType, rawHtml, extractedText, type, fetchedAt, null);
    }

    public Document(String url, String sourceType, String rawHtml, String extractedText, DocumentType type) {
        this(url, sourceType, rawHtml, extractedText, type, null, null);
    }

    public Document withExtractedText(String text) {
        return new Document(url, sourceType, rawHtml, text, type, fetchedAt, contentHash);
    }

    /** contentHash if the caller (ingestion pipeline) already computed one, otherwise a SHA-256 of extractedText. */
    public String resolvedContentHash() {
        if (contentHash != null) {
            return contentHash;
        }
        if (extractedText == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(extractedText.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
