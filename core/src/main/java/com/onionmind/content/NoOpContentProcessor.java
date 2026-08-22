package com.onionmind.content;

import org.springframework.stereotype.Component;

@Component
public class NoOpContentProcessor implements ContentProcessor {
    public ProcessingResult process(Document document) { return ProcessingResult.unchanged(document); }
    public boolean supports(DocumentType type) { return false; }
}
