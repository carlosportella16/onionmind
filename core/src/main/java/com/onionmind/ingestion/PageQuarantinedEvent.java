package com.onionmind.ingestion;

/**
 * The illegal-content guard blocked a page — published in the quarantine transaction so
 * every module that derived something from it (graph, change history) drops it too.
 */
public record PageQuarantinedEvent(String url, String host) {
}
