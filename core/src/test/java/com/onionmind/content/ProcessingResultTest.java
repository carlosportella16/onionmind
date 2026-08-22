package com.onionmind.content;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingResultTest {

    private final Document doc = new Document("http://example.onion", "tor", "<html></html>", "text", DocumentType.HTML, Instant.now());

    @Test
    void successHasSuccessStatusAndNoError() {
        ProcessingResult result = ProcessingResult.success(doc);

        assertThat(result.document()).isEqualTo(doc);
        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SUCCESS);
        assertThat(result.error()).isNull();
    }

    @Test
    void unchangedHasSkippedStatusAndNoError() {
        ProcessingResult result = ProcessingResult.unchanged(doc);

        assertThat(result.document()).isEqualTo(doc);
        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SKIPPED);
        assertThat(result.error()).isNull();
    }

    @Test
    void skippedHasSkippedStatusAndReason() {
        ProcessingResult result = ProcessingResult.skipped(doc, "unsupported type");

        assertThat(result.document()).isEqualTo(doc);
        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SKIPPED);
        assertThat(result.error()).isEqualTo("unsupported type");
    }

    @Test
    void failedHasFailedStatusAndError() {
        ProcessingResult result = ProcessingResult.failed(doc, "parse error");

        assertThat(result.document()).isEqualTo(doc);
        assertThat(result.status()).isEqualTo(ProcessingResult.Status.FAILED);
        assertThat(result.error()).isEqualTo("parse error");
    }
}
