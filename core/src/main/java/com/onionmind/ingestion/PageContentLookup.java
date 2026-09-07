package com.onionmind.ingestion;

import java.util.Optional;

/**
 * Public read port for a page's state (design.md D3) — {@code graph}/{@code intelligence}
 * (Phase 4) need it to react to {@link PageIndexedEvent}, but {@code PageRepository} is
 * {@code ingestion.internal} and off-limits to other modules. Never raw HTML.
 */
public interface PageContentLookup {

    /** The page's current extracted text, if it exists. */
    Optional<String> currentText(String url);

    /** The extracted text of the version immediately before {@code beforeVersion}, if archived. */
    Optional<String> previousVersionText(String url, int beforeVersion);

    /** The page's AI-assigned category (Fase 3), if one was generated — {@code intelligence}'s CATEGORY alert rules. */
    Optional<String> currentCategory(String url);
}
