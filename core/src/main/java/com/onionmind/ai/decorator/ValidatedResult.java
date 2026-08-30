package com.onionmind.ai.decorator;

/**
 * A provider response after the ValidationDecorator has parsed it. {@code primary} is the
 * task's main output (summary text / category / translation); {@code secondary} carries a
 * side value (translation's detected language) or null. Malformed JSON → {@code confidence 0}.
 */
public record ValidatedResult(String primary, String secondary, double confidence) {

    public static ValidatedResult invalid() {
        return new ValidatedResult(null, null, 0.0);
    }

    public boolean usable() {
        return primary != null && !primary.isBlank();
    }
}
