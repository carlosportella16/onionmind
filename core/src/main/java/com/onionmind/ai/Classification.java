package com.onionmind.ai;

/** A single-label classification with the confidence the model reported (SDD sec. 7.4). */
public record Classification(String category, double confidence) {
}
