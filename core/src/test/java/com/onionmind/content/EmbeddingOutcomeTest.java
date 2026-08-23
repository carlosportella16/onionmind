package com.onionmind.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingOutcomeTest {

    private Document doc() {
        return new Document("http://example.onion", "tor", "<html></html>", "text", DocumentType.HTML);
    }

    @Test
    void successMapsToEmbeddedWithNoError() {
        var outcome = EmbeddingOutcome.from(ProcessingResult.success(doc()));

        assertThat(outcome.status()).isEqualTo(EmbeddingOutcome.EMBEDDED);
        assertThat(outcome.errorMessage()).isNull();
    }

    @Test
    void unchangedSkipMapsToUnchanged() {
        var outcome = EmbeddingOutcome.from(ProcessingResult.unchanged(doc()));

        assertThat(outcome.status()).isEqualTo(EmbeddingOutcome.UNCHANGED);
    }

    @Test
    void skipWithoutExtractedTextMapsToNull() {
        var outcome = EmbeddingOutcome.from(ProcessingResult.skipped(doc(), "no extracted text to embed"));

        assertThat(outcome).isNull();
    }

    @Test
    void failureMapsToFailedTransientWithErrorMessage() {
        var outcome = EmbeddingOutcome.from(ProcessingResult.failed(doc(), "ollama unreachable"));

        assertThat(outcome.status()).isEqualTo(EmbeddingOutcome.FAILED_TRANSIENT);
        assertThat(outcome.errorMessage()).isEqualTo("ollama unreachable");
    }
}
