package com.onionmind.content;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record Document(
    String url, String sourceType, String rawHtml, String extractedText,
    String contentHash, DocumentType type
) {
    public Document(String url, String sourceType, String rawHtml, String extractedText, DocumentType type) {
        this(url, sourceType, rawHtml, extractedText, null, type);
    }

    public Document withExtractedText(String text) {
        return new Document(url, sourceType, rawHtml, text, contentHash, type);
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
