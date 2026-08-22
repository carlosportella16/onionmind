package com.onionmind.content;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTest {

    @Test
    void withExtractedTextUpdatesOnlyExtractedText() {
        Instant fetchedAt = Instant.parse("2026-08-22T00:00:00Z");
        Document original = new Document("http://example.onion", "tor", "<html></html>", null, DocumentType.HTML, fetchedAt);

        Document updated = original.withExtractedText("plain text content");

        assertThat(updated.url()).isEqualTo(original.url());
        assertThat(updated.sourceType()).isEqualTo(original.sourceType());
        assertThat(updated.rawHtml()).isEqualTo(original.rawHtml());
        assertThat(updated.type()).isEqualTo(original.type());
        assertThat(updated.fetchedAt()).isEqualTo(original.fetchedAt());
        assertThat(updated.extractedText()).isEqualTo("plain text content");
        assertThat(original.extractedText()).isNull();
    }

    @Test
    void legacyConstructorDefaultsContentHashToNull() {
        Document doc = new Document("http://example.onion", "tor", "<html></html>", "text", DocumentType.HTML);

        assertThat(doc.contentHash()).isNull();
    }

    @Test
    void resolvedContentHashComputesSha256WhenHashMissing() {
        Document doc = new Document("http://example.onion", "tor", "<html></html>", "same content", DocumentType.HTML);

        String hash1 = doc.resolvedContentHash();
        String hash2 = doc.resolvedContentHash();

        assertThat(hash1).isNotNull().hasSize(64); // hex-encoded SHA-256
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void resolvedContentHashReturnsExplicitHashWhenPresent() {
        Document doc = new Document("http://example.onion", "tor", "<html></html>", "text", "explicit-hash", DocumentType.HTML);

        assertThat(doc.resolvedContentHash()).isEqualTo("explicit-hash");
    }

    @Test
    void resolvedContentHashIsNullWhenNoExtractedText() {
        Document doc = new Document("http://example.onion", "tor", "<html></html>", null, DocumentType.HTML);

        assertThat(doc.resolvedContentHash()).isNull();
    }
}
