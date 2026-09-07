package com.onionmind.ingestion;

/**
 * Published by {@link IngestionPipeline} after a page is persisted (ADR-009, master-sdd sec.
 * 6.6). {@code ingestion} does not know who reacts to this — {@code graph}/{@code intelligence}
 * (Phase 4) do, via {@code @ApplicationModuleListener}. Deliberately lean: no page text, so the
 * Event Publication Registry never carries page content — listeners fetch text themselves via
 * {@link PageContentLookup}.
 */
public record PageIndexedEvent(Long pageId, String url, String contentHash, int version, boolean isNewVersion) {
}
