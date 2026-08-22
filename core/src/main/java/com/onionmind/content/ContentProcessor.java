package com.onionmind.content;

public interface ContentProcessor {
    ProcessingResult process(Document document);
    boolean supports(DocumentType type);
    default int order() { return 0; }
}
