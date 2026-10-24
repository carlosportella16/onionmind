package com.onionmind.content;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Embedding;
import com.onionmind.ai.TaskContext;
import com.onionmind.search.EmbeddingPoint;
import com.onionmind.search.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * First real ContentProcessor with AI (SDD Fase 2, sec. 3.5). Chunks the extracted text,
 * embeds every chunk in one batch via AIOrchestrator and upserts to Qdrant. Only the first
 * {@code embedding.max-chars-per-page} characters are embedded, so a 1.3 MB listing costs
 * ~25 chunks, not 3000 (replace-ollama-with-gemini-deepseek D6). A failed batch upserts
 * nothing — no partial page in the index. Identity is keyed by url: this can run before the
 * page is persisted, when no numeric page id exists yet.
 */
@Component
@ConditionalOnProperty(prefix = "embedding", name = "enabled", havingValue = "true")
public class EmbeddingProcessor implements ContentProcessor {

    private final AIOrchestrator aiOrchestrator;
    private final VectorStore vectorStore;
    private final int chunkSize;
    private final int overlapPercent;
    private final int maxCharsPerPage;

    public EmbeddingProcessor(AIOrchestrator aiOrchestrator,
                               VectorStore vectorStore,
                               @Value("${embedding.chunk-size}") int chunkSize,
                               @Value("${embedding.chunk-overlap-percent}") int overlapPercent,
                               @Value("${embedding.max-chars-per-page:50000}") int maxCharsPerPage) {
        this.aiOrchestrator = aiOrchestrator;
        this.vectorStore = vectorStore;
        this.chunkSize = chunkSize;
        this.overlapPercent = overlapPercent;
        this.maxCharsPerPage = maxCharsPerPage;
    }

    @Override
    public ProcessingResult process(Document document) {
        if (document.extractedText() == null || document.extractedText().isBlank()) {
            return ProcessingResult.skipped(document, "no extracted text to embed");
        }

        try {
            String currentHash = document.resolvedContentHash();
            if (vectorStore.hasUnchangedEmbedding(document.url(), currentHash)) {
                return ProcessingResult.unchanged(document);
            }

            String text = document.extractedText();
            if (text.length() > maxCharsPerPage) {
                text = text.substring(0, maxCharsPerPage);
            }
            List<String> chunks = TextChunker.chunk(text, chunkSize, overlapPercent);
            TaskContext ctx = TaskContext.batch(TaskContext.TaskType.EMBED, text.length() / 4, null, false);
            List<Embedding> embeddings = aiOrchestrator.embedAll(chunks, document.url(), ctx);

            List<EmbeddingPoint> points = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                points.add(new EmbeddingPoint(
                    UUID.nameUUIDFromBytes((document.url() + "-" + i).getBytes()),
                    embeddings.get(i).vector(),
                    document.url(),
                    i,
                    currentHash,
                    chunks.get(i)
                ));
            }

            vectorStore.upsert(points);
            return ProcessingResult.success(document);
        } catch (Exception e) {
            return ProcessingResult.failed(document, "Embedding failed: " + e.getMessage());
        }
    }

    @Override
    public boolean supports(DocumentType type) {
        return type == DocumentType.HTML;
    }

    @Override
    public int order() {
        return 100;
    }
}
