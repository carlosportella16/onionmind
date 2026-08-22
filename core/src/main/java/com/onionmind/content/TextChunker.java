package com.onionmind.content;

import java.util.ArrayList;
import java.util.List;

/** Splits text into overlapping windows for embedding (SDD Fase 2, sec. 3.4). */
public final class TextChunker {

    private static final int CHARS_PER_TOKEN = 4;

    private TextChunker() {
    }

    /**
     * @param text            source text
     * @param tokenChunkSize  approximate tokens per chunk
     * @param overlapPercent  0-100, portion of the chunk repeated at the start of the next one
     */
    public static List<String> chunk(String text, int tokenChunkSize, int overlapPercent) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int chunkChars = tokenChunkSize * CHARS_PER_TOKEN;
        int overlapChars = chunkChars * overlapPercent / 100;
        int step = Math.max(1, chunkChars - overlapChars);

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkChars, text.length());
            chunks.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
            start += step;
        }
        return chunks;
    }
}
