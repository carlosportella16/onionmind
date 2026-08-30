package com.onionmind.content;

/**
 * Marks the order 10-40 processors that add {@link Enrichment} to a page via the
 * AIOrchestrator. The ingestion pipeline uses it to know an AI step ran, and the AI
 * backfill job uses it to collect the processors to re-run.
 */
public interface AiEnrichingProcessor extends ContentProcessor {
}
