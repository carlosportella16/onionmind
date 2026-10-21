package com.onionmind.ai;

/**
 * A translation plus the source language the model detected. {@code detectedLanguage} may
 * be null when the model didn't report one.
 */
public record Translation(String text, String detectedLanguage, double confidence) {
}
