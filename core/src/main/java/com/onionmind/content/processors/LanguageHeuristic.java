package com.onionmind.content.processors;

import com.onionmind.content.Enrichment.LanguageTag;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stopword-frequency language guess. The only decision downstream actually needs is
 * "is this Portuguese?" (skip translation) plus a best-guess ISO code — a full 75-language
 * detector would be overkill here.
 *
 * ponytail: stopword heuristic over pt/en/es/fr/de only; swap in Lingua or
 * tika-langdetect if real multi-language accuracy ever matters.
 */
final class LanguageHeuristic {

    private static final Pattern WORDS = Pattern.compile("\\p{L}+");
    private static final double MIN_CONFIDENCE = 0.55;
    private static final int MIN_WORDS = 12;

    private static final Map<String, Set<String>> STOPWORDS = Map.of(
        "pt", Set.of("de", "que", "e", "o", "a", "do", "da", "em", "um", "para", "com", "não",
            "uma", "os", "no", "se", "na", "por", "mais", "as", "dos", "como", "mas", "foi", "ao"),
        "en", Set.of("the", "of", "and", "to", "in", "is", "that", "for", "it", "as", "with",
            "was", "on", "be", "at", "by", "this", "are", "from", "or", "an", "not", "have"),
        "es", Set.of("de", "la", "que", "el", "en", "y", "los", "del", "las", "un", "por",
            "con", "una", "su", "para", "es", "al", "lo", "como", "más", "pero", "sus"),
        "fr", Set.of("de", "la", "le", "et", "les", "des", "en", "un", "une", "du", "que",
            "qui", "dans", "pour", "pas", "sur", "au", "ce", "il", "est", "plus"),
        "de", Set.of("der", "die", "und", "in", "den", "von", "zu", "das", "mit", "sich",
            "auf", "für", "ist", "im", "dem", "nicht", "ein", "eine", "als", "auch"));

    private LanguageHeuristic() {
    }

    /** A confident guess, or empty when the text is too short or the result is ambiguous. */
    static Optional<LanguageTag> detect(String text) {
        if (text == null) {
            return Optional.empty();
        }
        List<String> words = WORDS.matcher(text.toLowerCase(Locale.ROOT)).results()
            .map(m -> m.group()).toList();
        if (words.size() < MIN_WORDS) {
            return Optional.empty();
        }

        String best = null;
        double bestRatio = 0;
        double secondRatio = 0;
        for (var entry : STOPWORDS.entrySet()) {
            long hits = words.stream().filter(entry.getValue()::contains).count();
            double ratio = (double) hits / words.size();
            if (ratio > bestRatio) {
                secondRatio = bestRatio;
                bestRatio = ratio;
                best = entry.getKey();
            } else if (ratio > secondRatio) {
                secondRatio = ratio;
            }
        }

        double confidence = bestRatio + bestRatio - secondRatio; // margin-weighted
        if (best == null || confidence < MIN_CONFIDENCE) {
            return Optional.empty();
        }
        return Optional.of(new LanguageTag(best, Math.min(1.0, confidence)));
    }
}
