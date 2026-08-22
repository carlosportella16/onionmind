package com.onionmind.search;

/** Wraps failures talking to Qdrant (network, non-2xx responses, serialization). */
public class QdrantException extends RuntimeException {
    public QdrantException(String message) {
        super(message);
    }

    public QdrantException(String message, Throwable cause) {
        super(message, cause);
    }
}
