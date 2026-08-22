package com.onionmind.content;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {

    @Test
    void blankTextProducesNoChunks() {
        assertThat(TextChunker.chunk(null, 512, 15)).isEmpty();
        assertThat(TextChunker.chunk("  ", 512, 15)).isEmpty();
    }

    @Test
    void shortTextFitsInOneChunk() {
        String text = "short onion page content";

        List<String> chunks = TextChunker.chunk(text, 512, 15);

        assertThat(chunks).containsExactly(text);
    }

    @Test
    void longTextIsSplitWithOverlap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        String text = sb.toString();

        List<String> chunks = TextChunker.chunk(text, 100, 15); // 100 tokens = 400 chars

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(text.startsWith(chunks.get(0))).isTrue();
        String last = chunks.get(chunks.size() - 1);
        assertThat(text.endsWith(last)).isTrue();
        // consecutive chunks must overlap by roughly 15% of the window
        String secondChunkTail = chunks.get(0).substring(chunks.get(0).length() - 60);
        assertThat(chunks.get(1)).startsWith(secondChunkTail);
    }

    @Test
    void zeroOverlapStillAdvances() {
        String text = "b".repeat(1000);

        List<String> chunks = TextChunker.chunk(text, 100, 0); // 400-char steps, no overlap

        assertThat(chunks).hasSize(3); // 0-400, 400-800, 800-1000
    }
}
