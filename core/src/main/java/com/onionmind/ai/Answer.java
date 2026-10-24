package com.onionmind.ai;

import java.util.List;

/**
 * Result of {@code answer}: the English answer and the ids of the sources it cites, only
 * among those sent ({@code S1}, {@code S2}…, in order). No valid citation → {@link #NO_ANSWER}.
 */
public record Answer(String text, List<String> citations, double confidence) {

    public static final String NO_ANSWER = "The indexed pages do not contain an answer to this question.";
}
