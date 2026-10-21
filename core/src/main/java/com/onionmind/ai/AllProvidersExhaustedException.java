package com.onionmind.ai;

/**
 * Every provider in the ladder failed hard (transport, not just low confidence). The
 * escalation loop only throws this when it has no usable result at all — a low-confidence
 * result is returned, not thrown (SDD sec. 7.2).
 */
public class AllProvidersExhaustedException extends RuntimeException {

    public AllProvidersExhaustedException(String message) {
        super(message);
    }

    public AllProvidersExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
