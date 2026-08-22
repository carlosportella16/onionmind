package com.onionmind.content.processors;

import com.onionmind.content.Document;
import com.onionmind.content.DocumentType;
import com.onionmind.content.ProcessingResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlSanitizerProcessorTest {

    private final HtmlSanitizerProcessor processor = new HtmlSanitizerProcessor();

    private Document docWithHtml(String html) {
        return new Document("http://example.onion", "tor", html, null, DocumentType.HTML, Instant.now());
    }

    @Test
    void removesScriptTags() {
        var doc = docWithHtml("<p>hello there, this is legitimate content</p><script>alert('xss')</script>");

        var result = processor.process(doc);

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SUCCESS);
        assertThat(result.document().extractedText()).contains("hello there, this is legitimate content");
        assertThat(result.document().extractedText()).doesNotContain("script");
        assertThat(result.document().extractedText()).doesNotContain("alert");
    }

    @Test
    void skipsEmptyHtml() {
        var result = processor.process(docWithHtml(""));

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SKIPPED);
        assertThat(result.error()).isEqualTo("empty html");
    }

    @Test
    void skipsShortText() {
        var result = processor.process(docWithHtml("<p>hi</p>"));

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SKIPPED);
        assertThat(result.error()).isEqualTo("text too short after sanitization");
    }

    @Test
    void supportsOnlyHtmlDocuments() {
        assertThat(processor.supports(DocumentType.HTML)).isTrue();
        assertThat(processor.supports(DocumentType.PDF)).isFalse();
        assertThat(processor.supports(DocumentType.IMAGE)).isFalse();
    }

    @Test
    void orderIsFirstInChain() {
        assertThat(processor.order()).isEqualTo(0);
    }
}
