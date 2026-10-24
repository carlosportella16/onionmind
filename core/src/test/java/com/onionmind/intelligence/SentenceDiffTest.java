package com.onionmind.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SentenceDiffTest {

    @Test
    void addedAndRemovedSentencesKeepTheirOriginalWording() {
        var r = SentenceDiff.diff(
            "Bem-vindo ao mercado. Vendemos livros raros. Entrega em 5 dias.",
            "Bem-vindo ao mercado. Entrega em 5 dias. Agora aceitamos Monero!");

        assertThat(r.added()).containsExactly("Agora aceitamos Monero!");
        assertThat(r.removed()).containsExactly("Vendemos livros raros.");
        assertThat(r.changedChars()).isEqualTo("Agora aceitamos Monero!".length() + "Vendemos livros raros.".length());
    }

    @Test
    void reorderingIsNotAChange() {
        assertThat(SentenceDiff.diff("Um. Dois. Três.", "Três. Um.\nDois.").isEmpty()).isTrue();
    }

    @Test
    void digitsCaseAndSpacingAreNoise() {
        var r = SentenceDiff.diff("Visitas: 1523. Atualizado em 20/10/2026.",
            "visitas:   1524.\nAtualizado em 21/10/2026.");

        assertThat(r.isEmpty()).isTrue();
    }

    @Test
    void nullOldTextMeansEverythingIsAdded() {
        assertThat(SentenceDiff.diff(null, "Novo. Texto.").added()).containsExactly("Novo.", "Texto.");
    }

    @Test
    void capCutsTheJoinedText() {
        assertThat(SentenceDiff.cap(List.of("abc", "def"), 5)).isEqualTo("abc\nd");
        assertThat(SentenceDiff.cap(List.of("abc"), 50)).isEqualTo("abc");
    }
}
