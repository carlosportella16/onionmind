package com.onionmind.ingestion;

import com.onionmind.content.DocumentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RawPageEventTest {

    @Test
    void toDocumentCreatesDocumentWithNullExtractedText() {
        Instant fetchedAt = Instant.parse("2026-08-22T00:00:00Z");
        var event = new RawPageEvent("http://example.onion", "tor", "<html></html>", fetchedAt);

        var doc = event.toDocument();

        assertThat(doc.url()).isEqualTo(event.url());
        assertThat(doc.sourceType()).isEqualTo(event.sourceType());
        assertThat(doc.rawHtml()).isEqualTo(event.html());
        assertThat(doc.type()).isEqualTo(DocumentType.HTML);
        assertThat(doc.fetchedAt()).isEqualTo(fetchedAt);
        assertThat(doc.extractedText()).isNull();
    }
}
