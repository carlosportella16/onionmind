package com.onionmind.content;

/**
 * What the order 10-40 processors produced for a page, plus the {@code ai_status} it maps
 * to. {@code FAILED_TRANSIENT} means some step failed but the page keeps whatever the
 * others generated — the backfill job will retry it.
 */
public record AiOutcome(Enrichment enrichment, String status, String errorMessage) {

    public static final String PROCESSED = "processed";
    public static final String FAILED_TRANSIENT = "failed_transient";

    public static AiOutcome processed(Enrichment enrichment) {
        return new AiOutcome(enrichment, PROCESSED, null);
    }

    public static AiOutcome partialFailure(Enrichment enrichment) {
        return new AiOutcome(enrichment, FAILED_TRANSIENT,
            "one or more AI enrichment steps failed; re-queued for backfill");
    }
}
