package com.onionmind.ingestion;

import com.onionmind.content.AiEnrichingProcessor;
import com.onionmind.content.ContentProcessor;
import com.onionmind.content.Document;
import com.onionmind.content.EmbeddingProcessor;
import com.onionmind.content.ProcessingResult;
import com.onionmind.ingestion.internal.PageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestionPipelineTest {

    @Mock
    private PageRepository repository;

    private RawPageEvent event() {
        return new RawPageEvent("http://example.onion", "tor", "<html>hi</html>", Instant.now());
    }

    private ContentProcessor processorReturning(int order, ProcessingResult result) {
        ContentProcessor p = mock(ContentProcessor.class);
        lenient().when(p.order()).thenReturn(order);
        lenient().when(p.supports(any())).thenReturn(true);
        lenient().when(p.process(any())).thenReturn(result);
        return p;
    }

    private AiEnrichingProcessor aiProcessorReturning(int order, ProcessingResult result) {
        AiEnrichingProcessor p = mock(AiEnrichingProcessor.class);
        lenient().when(p.order()).thenReturn(order);
        lenient().when(p.supports(any())).thenReturn(true);
        lenient().when(p.process(any())).thenReturn(result);
        return p;
    }

    @Test
    void executesProcessorsInOrderAndPersistsFinalDocument() {
        Document afterFirst = event().toDocument().withExtractedText("partial text content here");
        Document afterSecond = afterFirst.withExtractedText("final full text content here");

        ContentProcessor second = processorReturning(1, ProcessingResult.success(afterSecond));
        ContentProcessor first = processorReturning(0, ProcessingResult.success(afterFirst));

        var pipeline = new IngestionPipeline(List.of(second, first), repository);
        pipeline.process(event());

        InOrder inOrder = inOrder(first, second);
        inOrder.verify(first).process(any());
        inOrder.verify(second).process(any());

        verify(repository).upsertWithVersioning(
            argThat(d -> d.extractedText().equals("final full text content here")), isNull());
    }

    @Test
    void failedProcessorDoesNotAbortPipeline_persistsPriorResult() {
        Document afterFirst = event().toDocument().withExtractedText("text from the first processor");

        ContentProcessor first = processorReturning(0, ProcessingResult.success(afterFirst));
        ContentProcessor second = processorReturning(1, ProcessingResult.failed(afterFirst, "boom"));

        var pipeline = new IngestionPipeline(List.of(first, second), repository);
        pipeline.process(event());

        verify(repository).upsertWithVersioning(
            argThat(d -> d.extractedText().equals("text from the first processor")), isNull());
    }

    @Test
    void documentWithoutExtractedTextIsNotPersisted() {
        ContentProcessor noText = processorReturning(0, ProcessingResult.skipped(event().toDocument(), "empty html"));

        var pipeline = new IngestionPipeline(List.of(noText), repository);
        pipeline.process(event());

        verifyNoInteractions(repository);
    }

    @Test
    void haltStopsThePipelineAndQuarantinesWithoutPersistingContent() {
        Document afterSanitize = event().toDocument().withExtractedText("page text to be blocked");
        ContentProcessor sanitizer = processorReturning(0, ProcessingResult.success(afterSanitize));
        ContentProcessor guard = processorReturning(5, ProcessingResult.halt(afterSanitize, "url-denylist"));
        ContentProcessor embedding = processorReturning(100, ProcessingResult.success(afterSanitize));

        var pipeline = new IngestionPipeline(List.of(sanitizer, guard, embedding), repository);
        pipeline.process(event());

        verify(repository).quarantine(
            argThat(d -> d.extractedText().equals("page text to be blocked")), eq("url-denylist"));
        verify(repository, never()).upsertWithVersioning(any(), any());
        verify(embedding, never()).process(any());
    }

    @Test
    void aiEnrichingProcessorsAreNeverInvokedOnTheLivePath() {
        // fix-ingestion-stability: AI enrichment must never run on the Kafka listener thread —
        // AiEnrichmentBackfillJob is the only thing that invokes these processors now.
        Document afterSanitize = event().toDocument().withExtractedText("some page text here");
        ContentProcessor sanitizer = processorReturning(0, ProcessingResult.success(afterSanitize));
        AiEnrichingProcessor ai = aiProcessorReturning(30, ProcessingResult.success(afterSanitize));

        var pipeline = new IngestionPipeline(List.of(sanitizer, ai), repository);
        pipeline.process(event());

        verify(ai, never()).process(any());
        verify(repository, never()).updateAiFields(any(), any());
        verify(repository).upsertWithVersioning(any(), isNull());
    }

    @Test
    void embeddingProcessorIsNeverInvokedOnTheLivePath() {
        // fix-ingestion-stability: embedding must never run on the Kafka listener thread —
        // EmbeddingBackfillJob is the only thing that invokes it now.
        Document afterSanitize = event().toDocument().withExtractedText("some page text here");
        ContentProcessor sanitizer = processorReturning(0, ProcessingResult.success(afterSanitize));

        EmbeddingProcessor embedding = mock(EmbeddingProcessor.class);
        lenient().when(embedding.order()).thenReturn(100);

        var pipeline = new IngestionPipeline(List.of(sanitizer, embedding), repository);
        pipeline.process(event());

        verify(embedding, never()).process(any());
        verify(repository).upsertWithVersioning(any(), isNull());
    }
}
