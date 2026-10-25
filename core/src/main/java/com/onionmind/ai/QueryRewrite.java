package com.onionmind.ai;

/** Result of {@code rewriteQuery}: the question as a short English search query. */
public record QueryRewrite(String query, double confidence) {
}
