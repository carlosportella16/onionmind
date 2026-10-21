package com.onionmind.content;

/**
 * What EmbeddingProcessor did with a page, translated into the columns added by migration V3
 * (embedding_status / embedding_attempted_at / embedding_error_message). {@link #from} returns
 * null when there is nothing worth persisting yet — e.g. the page has no extracted text at all.
 */
public record EmbeddingOutcome(String status, String errorMessage) {

    public static final String EMBEDDED = "embedded";
    public static final String UNCHANGED = "unchanged";
    public static final String FAILED_TRANSIENT = "failed_transient";

    public static EmbeddingOutcome from(ProcessingResult result) {
        return switch (result.status()) {
            case SUCCESS -> new EmbeddingOutcome(EMBEDDED, null);
            case SKIPPED -> result.error() == null ? new EmbeddingOutcome(UNCHANGED, null) : null;
            case FAILED -> new EmbeddingOutcome(FAILED_TRANSIENT, result.error());
            case HALT -> null; // guard stops the pipeline before EmbeddingProcessor ever runs
        };
    }
}
