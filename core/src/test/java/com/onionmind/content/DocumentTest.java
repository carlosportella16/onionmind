package com.onionmind.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTest {

    @Test
    void withExtractedTextUpdatesOnlyExtractedText() {
        Document original = new Document("http://example.onion", "tor", "<html></html>", null, DocumentType.HTML);

        Document updated = original.withExtractedText("plain text content");

        assertThat(updated.url()).isEqualTo(original.url());
        assertThat(updated.sourceType()).isEqualTo(original.sourceType());
        assertThat(updated.rawHtml()).isEqualTo(original.rawHtml());
        assertThat(updated.type()).isEqualTo(original.type());
        assertThat(updated.extractedText()).isEqualTo("plain text content");
        assertThat(original.extractedText()).isNull();
    }
}
