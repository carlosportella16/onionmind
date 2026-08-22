package com.onionmind.ingestion.internal;

public record PageEntity(
    Long id, String url, String contentHash, int version, String extractedText
) {}
