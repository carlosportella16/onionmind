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
 * embeds each chunk via AIOrchestrator and upserts to Qdrant. Runs before the page is
 * persisted (same as every other ContentProcessor in the pipeline), so identity is keyed
 * by url — never a numeric page id, which doesn't exist yet at this point.
 */
@Component
@ConditionalOnProperty(prefix = "embedding", name = "enabled", havingValue = "true")
public class EmbeddingProcessor implements ContentProcessor {

    private final AIOrchestrator aiOrchestrator;
    private final VectorStore vectorStore;
    private final int chunkSize;
    private final int overlapPercent;

    public EmbeddingProcessor(AIOrchestrator aiOrchestrator,
                               VectorStore vectorStore,
                               @Value("${embedding.chunk-size}") int chunkSize,
                               @Value("${embedding.chunk-overlap-percent}") int overlapPercent) {
        this.aiOrchestrator = aiOrchestrator;
        this.vectorStore = vectorStore;
        this.chunkSize = chunkSize;
        this.overlapPercent = overlapPercent;
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

            List<String> chunks = TextChunker.chunk(document.extractedText(), chunkSize, overlapPercent);
            List<EmbeddingPoint> points = new ArrayList<>(chunks.size());

            for (int i = 0; i < chunks.size(); i++) {
                String chunkText = chunks.get(i);
                TaskContext ctx = new TaskContext(
                    TaskContext.TaskType.EMBED, chunkText.length() / 4, null, false, false, null, 0
                );
                try {
                    Embedding embedding = aiOrchestrator.embed(chunkText, ctx);
                    points.add(new EmbeddingPoint(
                        UUID.nameUUIDFromBytes((document.url() + "-" + i).getBytes()),
                        embedding.vector(),
                        document.url(),
                        i,
                        currentHash
                    ));
                } catch (Exception e) {
                    return ProcessingResult.failed(document, "Embedding failed on chunk " + i + ": " + e.getMessage());
                }
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
