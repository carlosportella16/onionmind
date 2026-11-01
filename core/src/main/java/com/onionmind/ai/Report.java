package com.onionmind.ai;

import java.util.List;

/**
 * Result of {@code report}: an English report and the ids of the sources it cites, only among
 * those sent. No valid citation, or nothing to report on → {@link #NO_MATERIAL}.
 */
public record Report(String text, List<String> citations, double confidence) {

    public static final String NO_MATERIAL = "The indexed pages have no material on this topic in the period.";
}
