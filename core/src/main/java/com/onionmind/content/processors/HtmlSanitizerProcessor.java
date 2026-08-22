package com.onionmind.content.processors;

import com.onionmind.content.ContentProcessor;
import com.onionmind.content.Document;
import com.onionmind.content.DocumentType;
import com.onionmind.content.ProcessingResult;
import org.jsoup.Jsoup;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Component;

@Component
public class HtmlSanitizerProcessor implements ContentProcessor {
    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
        .allowElements("p", "a", "b", "i", "em", "strong",
                        "ul", "ol", "li", "h1", "h2", "h3", "h4",
                        "table", "tr", "td", "th", "pre", "code", "blockquote")
        .allowUrlProtocols("http", "https")
        .allowAttributes("href").onElements("a")
        .requireRelNofollowOnLinks()
        .toFactory();

    @Override
    public boolean supports(DocumentType type) { return type == DocumentType.HTML; }

    @Override
    public int order() { return 0; } // first in the chain, always

    @Override
    public ProcessingResult process(Document doc) {
        if (doc.rawHtml() == null || doc.rawHtml().isBlank()) {
            return ProcessingResult.skipped(doc, "empty html");
        }
        try {
            String sanitized = POLICY.sanitize(doc.rawHtml());
            String text = Jsoup.parse(sanitized).text();
            if (text.length() < 20) {
                return ProcessingResult.skipped(doc, "text too short after sanitization");
            }
            return ProcessingResult.success(doc.withExtractedText(text));
        } catch (Exception e) {
            return ProcessingResult.failed(doc, "sanitization error: " + e.getMessage());
        }
    }
}
