package com.onionmind.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpContentProcessorTest {

    private final NoOpContentProcessor processor = new NoOpContentProcessor();

    @Test
    void processReturnsSkippedResult() {
        Document doc = new Document("http://example.onion", "tor", "<html></html>", null, DocumentType.HTML);

        ProcessingResult result = processor.process(doc);

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SKIPPED);
        assertThat(result.document()).isEqualTo(doc);
    }

    @Test
    void supportsReturnsFalseForAllTypes() {
        assertThat(processor.supports(DocumentType.HTML)).isFalse();
        assertThat(processor.supports(DocumentType.PDF)).isFalse();
        assertThat(processor.supports(DocumentType.IMAGE)).isFalse();
    }
}
