package com.onionmind.ingestion.internal;

/** Result of {@link PageRepository#upsertWithVersioning} — enough for {@code IngestionPipeline} to publish {@code PageIndexedEvent} (Phase 4, ADR-009). */
public record UpsertOutcome(Long pageId, int version, boolean isNewVersion) {
}
