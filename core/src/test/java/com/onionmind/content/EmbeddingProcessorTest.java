package com.onionmind.content;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Classification;
import com.onionmind.ai.Embedding;
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
    private static final int MAX_CHARS = 50_000;

    private static EmbeddingProcessor processor(AIOrchestrator ai, VectorStore store) {
        return new EmbeddingProcessor(ai, store, CHUNK_SIZE_TOKENS, OVERLAP_PERCENT, MAX_CHARS);
    }

    @Test
    void skipsDocumentsWithoutExtractedText() {
        FakeVectorStore store = new FakeVectorStore();
        EmbeddingProcessor processor = processor(new FakeAIOrchestrator(), store);
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
        EmbeddingProcessor processor = processor(ai, store);
        Document doc = new Document("http://example.onion", "tor", "<html></html>", "unchanged content", DocumentType.HTML);

        ProcessingResult result = processor.process(doc);

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SKIPPED);
        assertThat(ai.batchCalls).isZero();
        assertThat(store.upsertCalls).isZero();
    }

    @Test
    void newContentIsChunkedEmbeddedAndUpserted() {
        FakeVectorStore store = new FakeVectorStore();
        FakeAIOrchestrator ai = new FakeAIOrchestrator();
        EmbeddingProcessor processor = processor(ai, store);
        String longText = "x".repeat(1000); // spans multiple 400-char chunks
        Document doc = new Document("http://example.onion", "tor", "<html></html>", longText, DocumentType.HTML);

        ProcessingResult result = processor.process(doc);

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SUCCESS);
        assertThat(ai.batchCalls).isEqualTo(1);
        assertThat(ai.lastTexts).hasSizeGreaterThan(1);
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
    void allChunksOfAPageGoInOneBatchInOrderWithTheirText() {
        FakeVectorStore store = new FakeVectorStore();
        FakeAIOrchestrator ai = new FakeAIOrchestrator();
        EmbeddingProcessor processor = processor(ai, store);
        String text = "a".repeat(400) + "b".repeat(400) + "c".repeat(400);

        processor.process(new Document("http://example.onion", "tor", null, text, DocumentType.HTML));

        assertThat(ai.batchCalls).isEqualTo(1);
        assertThat(ai.lastTitle).isEqualTo("http://example.onion");
        assertThat(ai.lastCtx.interactive()).isFalse(); // document role, not query
        assertThat(store.lastUpsertedPoints).extracting(EmbeddingPoint::chunkIndex)
            .containsExactly(java.util.stream.IntStream.range(0, ai.lastTexts.size()).boxed().toArray(Integer[]::new));
        assertThat(store.lastUpsertedPoints).extracting(EmbeddingPoint::text).containsExactlyElementsOf(ai.lastTexts);
    }

    @Test
    void onlyTheFirstMaxCharsOfAPageAreChunkedAndEmbedded() {
        FakeVectorStore store = new FakeVectorStore();
        FakeAIOrchestrator ai = new FakeAIOrchestrator();
        EmbeddingProcessor processor = new EmbeddingProcessor(ai, store, CHUNK_SIZE_TOKENS, 0, 1000);
        String text = "h".repeat(1000) + "TAIL" + "t".repeat(1_300_000); // a 1.3 MB listing

        ProcessingResult result = processor.process(new Document("http://big.onion", "tor", null, text, DocumentType.HTML));

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SUCCESS);
        assertThat(ai.lastTexts).hasSize(3); // 1000 chars in 400-char chunks, no overlap
        assertThat(String.join("", ai.lastTexts)).isEqualTo("h".repeat(1000));
    }

    @Test
    void embeddingFailureReturnsFailedResultWithoutUpserting() {
        FakeVectorStore store = new FakeVectorStore();
        FakeAIOrchestrator ai = new FakeAIOrchestrator();
        ai.failOnCall = 0;
        EmbeddingProcessor processor = processor(ai, store);
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
        EmbeddingProcessor processor = processor(new FakeAIOrchestrator(), store);
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
        EmbeddingProcessor processor = processor(new FakeAIOrchestrator(), store);
        Document doc = new Document("http://example.onion", "tor", "<html></html>", "some content", DocumentType.HTML);

        ProcessingResult result = processor.process(doc);

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.FAILED);
    }

    @Test
    void supportsOnlyHtml() {
        EmbeddingProcessor processor = processor(new FakeAIOrchestrator(), new FakeVectorStore());

        assertThat(processor.supports(DocumentType.HTML)).isTrue();
        assertThat(processor.supports(DocumentType.PDF)).isFalse();
        assertThat(processor.supports(DocumentType.IMAGE)).isFalse();
    }

    @Test
    void runsAfterSanitizerInPipelineOrder() {
        EmbeddingProcessor processor = processor(new FakeAIOrchestrator(), new FakeVectorStore());

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
        public com.onionmind.ai.EntityExtraction extractEntities(String text, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.onionmind.ai.QueryRewrite rewriteQuery(String question, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.onionmind.ai.Answer answer(String question, java.util.List<String> sources, TaskContext ctx) {
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

        int batchCalls = 0;
        List<String> lastTexts = List.of();
        String lastTitle;
        TaskContext lastCtx;

        /** One batch, failing as a whole like GeminiEmbedder does. */
        @Override
        public List<Embedding> embedAll(List<String> texts, String title, TaskContext ctx) {
            batchCalls++;
            lastTexts = texts;
            lastTitle = title;
            lastCtx = ctx;
            if (failOnCall >= 0) {
                throw new RuntimeException("simulated failure");
            }
            return texts.stream().map(t -> new Embedding(new float[]{0.1f, 0.2f, 0.3f}, 1.0)).toList();
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
