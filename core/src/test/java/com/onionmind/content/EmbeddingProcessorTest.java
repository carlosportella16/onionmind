package com.onionmind.content;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Classification;
import com.onionmind.ai.Embedding;
import com.onionmind.ai.Entity;
import com.onionmind.ai.LanguageDetection;
import com.onionmind.ai.Summary;
import com.onionmind.ai.TaskContext;
import com.onionmind.ai.Translation;
import com.onionmind.search.EmbeddingPoint;
import com.onionmind.search.SemanticSearchHit;
import com.onionmind.search.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingProcessorTest {

    private static final int CHUNK_SIZE_TOKENS = 100; // 400 chars
    private static final int OVERLAP_PERCENT = 15;

    @Test
    void skipsDocumentsWithoutExtractedText() {
        FakeVectorStore store = new FakeVectorStore();
        EmbeddingProcessor processor = new EmbeddingProcessor(new FakeAIOrchestrator(), store, CHUNK_SIZE_TOKENS, OVERLAP_PERCENT);
        Document doc = new Document("http://example.onion", "tor", "<html></html>", null, DocumentType.HTML);

        ProcessingResult result = processor.process(doc);

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SKIPPED);
        assertThat(store.upsertCalls).isZero();
    }

    @Test
    void unchangedContentSkipsEmbeddingAltogether() {
        FakeVectorStore store = new FakeVectorStore();
        store.unchanged = true;
        FakeAIOrchestrator ai = new FakeAIOrchestrator();
        EmbeddingProcessor processor = new EmbeddingProcessor(ai, store, CHUNK_SIZE_TOKENS, OVERLAP_PERCENT);
        Document doc = new Document("http://example.onion", "tor", "<html></html>", "unchanged content", DocumentType.HTML);

        ProcessingResult result = processor.process(doc);

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SKIPPED);
        assertThat(ai.embedCalls).isZero();
        assertThat(store.upsertCalls).isZero();
    }

    @Test
    void newContentIsChunkedEmbeddedAndUpserted() {
        FakeVectorStore store = new FakeVectorStore();
        FakeAIOrchestrator ai = new FakeAIOrchestrator();
        EmbeddingProcessor processor = new EmbeddingProcessor(ai, store, CHUNK_SIZE_TOKENS, OVERLAP_PERCENT);
        String longText = "x".repeat(1000); // spans multiple 400-char chunks
        Document doc = new Document("http://example.onion", "tor", "<html></html>", longText, DocumentType.HTML);

        ProcessingResult result = processor.process(doc);

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SUCCESS);
        assertThat(ai.embedCalls).isGreaterThan(1);
        assertThat(store.upsertCalls).isEqualTo(1);
        assertThat(store.lastUpsertedPoints).hasSizeGreaterThan(1);
        Set<Integer> chunkIndexes = new HashSet<>();
        for (EmbeddingPoint p : store.lastUpsertedPoints) {
            assertThat(p.url()).isEqualTo("http://example.onion");
            chunkIndexes.add(p.chunkIndex());
        }
        assertThat(chunkIndexes).hasSize(store.lastUpsertedPoints.size()); // no duplicate chunk indexes
    }

    @Test
    void embeddingFailureReturnsFailedResultWithoutUpserting() {
        FakeVectorStore store = new FakeVectorStore();
        FakeAIOrchestrator ai = new FakeAIOrchestrator();
        ai.failOnCall = 0;
        EmbeddingProcessor processor = new EmbeddingProcessor(ai, store, CHUNK_SIZE_TOKENS, OVERLAP_PERCENT);
        Document doc = new Document("http://example.onion", "tor", "<html></html>", "will fail", DocumentType.HTML);

        ProcessingResult result = processor.process(doc);

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.FAILED);
        assertThat(result.error()).contains("simulated failure");
        assertThat(store.upsertCalls).isZero();
    }

    @Test
    void vectorStoreThrowingOnHasUnchangedReturnsFailedInsteadOfPropagating() {
        FakeVectorStore store = new FakeVectorStore();
        store.throwOnHasUnchanged = true;
        EmbeddingProcessor processor = new EmbeddingProcessor(new FakeAIOrchestrator(), store, CHUNK_SIZE_TOKENS, OVERLAP_PERCENT);
        Document doc = new Document("http://example.onion", "tor", "<html></html>", "some content", DocumentType.HTML);

        // Regression: Qdrant unreachable here must not throw out of process() — IngestionPipeline
        // only tolerates a returned FAILED result, an escaping exception drops the whole page.
        ProcessingResult result = processor.process(doc);

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.FAILED);
    }

    @Test
    void vectorStoreThrowingOnUpsertReturnsFailedInsteadOfPropagating() {
        FakeVectorStore store = new FakeVectorStore();
        store.throwOnUpsert = true;
        EmbeddingProcessor processor = new EmbeddingProcessor(new FakeAIOrchestrator(), store, CHUNK_SIZE_TOKENS, OVERLAP_PERCENT);
        Document doc = new Document("http://example.onion", "tor", "<html></html>", "some content", DocumentType.HTML);

        ProcessingResult result = processor.process(doc);

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.FAILED);
    }

    @Test
    void supportsOnlyHtml() {
        EmbeddingProcessor processor = new EmbeddingProcessor(new FakeAIOrchestrator(), new FakeVectorStore(), CHUNK_SIZE_TOKENS, OVERLAP_PERCENT);

        assertThat(processor.supports(DocumentType.HTML)).isTrue();
        assertThat(processor.supports(DocumentType.PDF)).isFalse();
        assertThat(processor.supports(DocumentType.IMAGE)).isFalse();
    }

    @Test
    void runsAfterSanitizerInPipelineOrder() {
        EmbeddingProcessor processor = new EmbeddingProcessor(new FakeAIOrchestrator(), new FakeVectorStore(), CHUNK_SIZE_TOKENS, OVERLAP_PERCENT);

        assertThat(processor.order()).isEqualTo(100);
    }

    private static class FakeAIOrchestrator implements AIOrchestrator {
        int embedCalls = 0;
        int failOnCall = -1;

        @Override
        public Summary summarize(String text, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Classification classify(String text, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Translation translate(String text, String targetLang, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LanguageDetection detectLanguage(String text, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Entity> extractEntities(String text, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Summary summarizeDiff(String previousText, String currentText, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Embedding embed(String text, TaskContext ctx) {
            embedCalls++;
            if (embedCalls - 1 == failOnCall) {
                throw new RuntimeException("simulated failure");
            }
            return new Embedding(new float[]{0.1f, 0.2f, 0.3f}, 1.0);
        }
    }

    private static class FakeVectorStore implements VectorStore {
        boolean unchanged = false;
        boolean throwOnHasUnchanged = false;
        boolean throwOnUpsert = false;
        int upsertCalls = 0;
        List<EmbeddingPoint> lastUpsertedPoints = new ArrayList<>();

        @Override
        public void ensureCollection() {
        }

        @Override
        public void upsert(List<EmbeddingPoint> points) {
            if (throwOnUpsert) {
                throw new RuntimeException("qdrant unreachable");
            }
            upsertCalls++;
            lastUpsertedPoints = points;
        }

        @Override
        public List<SemanticSearchHit> search(float[] queryVector, int topK) {
            return List.of();
        }

        @Override
        public boolean hasUnchangedEmbedding(String url, String contentHash) {
            if (throwOnHasUnchanged) {
                throw new RuntimeException("qdrant unreachable");
            }
            return unchanged;
        }

        @Override
        public void deleteByUrl(String url) {
        }
    }
}
