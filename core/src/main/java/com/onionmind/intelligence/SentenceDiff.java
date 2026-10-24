package com.onionmind.intelligence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Set difference over normalized sentences (design D8): reordering is not a change, and
 * digits are masked so visit counters, dates and prices never look like content changes.
 * ponytail: repeated sentences collapse in the set; switch to a multiset if that hides real changes.
 */
final class SentenceDiff {

    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+|\\R+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** Sentences only in the new / only in the old text (original wording), and their total length. */
    record Result(List<String> added, List<String> removed, int changedChars) {

        boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty();
        }
    }

    private SentenceDiff() {
    }

    static Result diff(String oldText, String newText) {
        Map<String, String> before = sentences(oldText);
        Map<String, String> after = sentences(newText);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        after.forEach((key, original) -> {
            if (!before.containsKey(key)) added.add(original);
        });
        before.forEach((key, original) -> {
            if (!after.containsKey(key)) removed.add(original);
        });
        int changed = added.stream().mapToInt(String::length).sum() + removed.stream().mapToInt(String::length).sum();
        return new Result(added, removed, changed);
    }

    /** normalized -> first original wording, in text order. */
    private static Map<String, String> sentences(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) {
            return out;
        }
        for (String s : SENTENCE_BREAK.split(text)) {
            String original = SPACES.matcher(s.strip()).replaceAll(" ");
            if (!original.isEmpty()) {
                out.putIfAbsent(normalize(original), original);
            }
        }
        return out;
    }

    static String normalize(String sentence) {
        return DIGITS.matcher(sentence.toLowerCase(Locale.ROOT)).replaceAll("#");
    }

    /** Joins sentences one per line, cut at {@code maxChars}. */
    static String cap(List<String> sentences, int maxChars) {
        String joined = String.join("\n", sentences);
        return joined.length() <= maxChars ? joined : joined.substring(0, maxChars);
    }
}
