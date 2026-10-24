package com.onionmind.content;

/**
 * What the order 10-40 processors produced for a page, plus the {@code ai_status} it maps
 * to. {@code FAILED_TRANSIENT} means some step failed but the page keeps whatever the
 * others generated — the backfill job will retry it. {@code FAILED_PERMANENT} is the same
 * failure once the page ran out of attempts ({@code ai.backfill.max-attempts}): no more retries.
 */
public record AiOutcome(Enrichment enrichment, String status, String errorMessage) {

    public static final String PROCESSED = "processed";
    public static final String FAILED_TRANSIENT = "failed_transient";
    public static final String FAILED_PERMANENT = "failed_permanent";

    public static AiOutcome processed(Enrichment enrichment) {
        return new AiOutcome(enrichment, PROCESSED, null);
    }

    public static AiOutcome partialFailure(Enrichment enrichment) {
        return new AiOutcome(enrichment, FAILED_TRANSIENT,
            "one or more AI enrichment steps failed; re-queued for backfill");
    }

    public static AiOutcome permanentFailure(Enrichment enrichment) {
        return new AiOutcome(enrichment, FAILED_PERMANENT,
            "one or more AI enrichment steps failed; max attempts reached, no more retries");
    }
}
