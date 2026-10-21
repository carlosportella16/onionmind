package com.onionmind.acceptance;

import org.junit.jupiter.api.Test;

import static com.onionmind.acceptance.FakeAiProviders.cosine;
import static com.onionmind.acceptance.FakeAiProviders.embed;
import static org.assertj.core.api.Assertions.assertThat;

/** The fake embedding must separate concepts the way the scenarios assume (min-score 0.45). */
class FakeAiProvidersTest {

    private static final double MIN_SCORE = 0.45;

    @Test
    void paraphraseOfSameConceptScoresAboveMinScore() {
        float[] query = embed("criptomoedas");
        float[] page = embed("Mercado aceita moeda digital anônima e registra tudo num blockchain descentralizado.");

        assertThat(cosine(query, page)).isGreaterThan(MIN_SCORE);
    }

    @Test
    void unrelatedTextScoresBelowMinScore() {
        float[] query = embed("receita de bolo de cenoura");
        float[] page = embed("Mercado aceita moeda digital anônima e registra tudo num blockchain descentralizado.");

        assertThat(cosine(query, page)).isLessThan(MIN_SCORE);
    }

    @Test
    void distinctConceptsScoreBelowMinScore() {
        assertThat(cosine(embed("criptomoedas"), embed("vazamento de senhas e credenciais"))).isLessThan(MIN_SCORE);
    }

    @Test
    void vectorsAreUnitLength() {
        float[] v = embed("bitcoin e outras coisas");
        assertThat(cosine(v, v)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(cosine(embed(""), embed(""))).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }
}
