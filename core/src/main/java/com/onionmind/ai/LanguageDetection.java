package com.onionmind.ai;

/** An ISO-639-1 language code the model settled on, plus its confidence. */
public record LanguageDetection(String code, double confidence) {
}
