package com.onionmind.content.processors;

import com.onionmind.content.Enrichment.LanguageTag;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageHeuristicTest {

    @Test
    void detectsPortuguese() {
        String pt = "Este é um fórum sobre moeda digital e privacidade na rede. "
            + "Os usuários trocam informações e não revelam a identidade para mais ninguém.";

        Optional<LanguageTag> result = LanguageHeuristic.detect(pt);

        assertThat(result).get().extracting(LanguageTag::code).isEqualTo("pt");
    }

    @Test
    void detectsEnglish() {
        String en = "This is a forum about digital currency and privacy on the network. "
            + "The users share information and do not reveal their identity to anyone at all.";

        Optional<LanguageTag> result = LanguageHeuristic.detect(en);

        assertThat(result).get().extracting(LanguageTag::code).isEqualTo("en");
    }

    @Test
    void returnsEmptyForTextTooShort() {
        assertThat(LanguageHeuristic.detect("hello world")).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoStopwordsMatch() {
        String gibberish = "xyzzy plugh frobnicate wibble wobble flibbertigibbet quux "
            + "grault garply waldo fred plugh xyzzy thud spam ham eggs";

        assertThat(LanguageHeuristic.detect(gibberish)).isEmpty();
    }

    @Test
    void returnsEmptyForNull() {
        assertThat(LanguageHeuristic.detect(null)).isEmpty();
    }
}
