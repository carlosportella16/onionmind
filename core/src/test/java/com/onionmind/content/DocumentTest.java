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
}
