package com.onionmind.intelligence;

import java.time.Instant;

/** Read-model returned by {@link DiffController} — public like {@code Entity}/{@code Summary}, since it crosses the HTTP boundary. */
public record PageDiffView(int fromVersion, int toVersion, String text, double confidence, Instant generatedAt) {
}
