package com.onionmind.ingestion;

import com.onionmind.content.AiEnrichingProcessor;
import com.onionmind.content.AiOutcome;
import com.onionmind.content.ContentProcessor;
import com.onionmind.content.Document;
import com.onionmind.content.EmbeddingOutcome;
import com.onionmind.content.EmbeddingProcessor;
import com.onionmind.content.ProcessingResult;
import com.onionmind.ingestion.internal.PageRepository;
import com.onionmind.ingestion.internal.UpsertOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestionPipelineTest {

    @Mock
    private PageRepository repository;

    @Mock
    private ApplicationEventPublisher events;

    @BeforeEach
    void stubUpsertOutcome() {
        lenient().when(repository.upsertWithVersioning(any(), any()))
            .thenReturn(new UpsertOutcome(1L, 1, true));
    }

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

        var pipeline = new IngestionPipeline(List.of(second, first), repository, events);
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

        var pipeline = new IngestionPipeline(List.of(first, second), repository, events);
        pipeline.process(event());

        verify(repository).upsertWithVersioning(
            argThat(d -> d.extractedText().equals("text from the first processor")), isNull());
    }

    @Test
    void documentWithoutExtractedTextIsNotPersisted() {
        ContentProcessor noText = processorReturning(0, ProcessingResult.skipped(event().toDocument(), "empty html"));

        var pipeline = new IngestionPipeline(List.of(noText), repository, events);
        pipeline.process(event());

        verifyNoInteractions(repository);
    }

    @Test
    void haltStopsThePipelineAndQuarantinesWithoutPersistingContent() {
        Document afterSanitize = event().toDocument().withExtractedText("page text to be blocked");
        ContentProcessor sanitizer = processorReturning(0, ProcessingResult.success(afterSanitize));
        ContentProcessor guard = processorReturning(5, ProcessingResult.halt(afterSanitize, "url-denylist"));
        ContentProcessor embedding = processorReturning(100, ProcessingResult.success(afterSanitize));

        var pipeline = new IngestionPipeline(List.of(sanitizer, guard, embedding), repository, events);
        pipeline.process(event());

        verify(repository).quarantine(
            argThat(d -> d.extractedText().equals("page text to be blocked")), eq("url-denylist"));
        verify(repository, never()).upsertWithVersioning(any(), any());
        verify(embedding, never()).process(any());
    }

    @Test
    void aiEnrichmentIsAggregatedAndPersistedAfterUpsert() {
        Document afterSanitize = event().toDocument().withExtractedText("some page text here");
        ContentProcessor sanitizer = processorReturning(0, ProcessingResult.success(afterSanitize));
        AiEnrichingProcessor ai = aiProcessorReturning(30, ProcessingResult.success(afterSanitize));

        var pipeline = new IngestionPipeline(List.of(sanitizer, ai), repository, events);
        pipeline.process(event());

        InOrder inOrder = inOrder(repository);
        inOrder.verify(repository).upsertWithVersioning(any(), any());
        inOrder.verify(repository).updateAiFields(eq("http://example.onion"),
            argThat(o -> o.status().equals(AiOutcome.PROCESSED)));
    }

    @Test
    void aiProcessorFailureIsPersistedAsPartialFailure() {
        Document afterSanitize = event().toDocument().withExtractedText("some page text here");
        ContentProcessor sanitizer = processorReturning(0, ProcessingResult.success(afterSanitize));
        AiEnrichingProcessor ai = aiProcessorReturning(30, ProcessingResult.failed(afterSanitize, "providers down"));

        new IngestionPipeline(List.of(sanitizer, ai), repository, events).process(event());

        verify(repository).updateAiFields(any(), argThat(o -> o.status().equals(AiOutcome.FAILED_TRANSIENT)));
    }

    @Test
    void unchangedAiContentDoesNotTouchAiFields() {
        Document afterSanitize = event().toDocument().withExtractedText("some page text here");
        ContentProcessor sanitizer = processorReturning(0, ProcessingResult.success(afterSanitize));
        AiEnrichingProcessor ai = aiProcessorReturning(30, ProcessingResult.unchanged(afterSanitize));

        new IngestionPipeline(List.of(sanitizer, ai), repository, events).process(event());

        verify(repository).upsertWithVersioning(any(), any());
        verify(repository, never()).updateAiFields(any(), any());
    }

    @Test
    void embeddingProcessorOutcomeIsPassedToRepository() {
        Document afterSanitize = event().toDocument().withExtractedText("some page text here");
        ContentProcessor sanitizer = processorReturning(0, ProcessingResult.success(afterSanitize));

        EmbeddingProcessor embedding = mock(EmbeddingProcessor.class);
        lenient().when(embedding.order()).thenReturn(100);
        lenient().when(embedding.supports(any())).thenReturn(true);
        lenient().when(embedding.process(any())).thenReturn(ProcessingResult.success(afterSanitize));

        var pipeline = new IngestionPipeline(List.of(sanitizer, embedding), repository, events);
        pipeline.process(event());

        verify(repository).upsertWithVersioning(any(),
            argThat(outcome -> outcome != null && outcome.status().equals(EmbeddingOutcome.EMBEDDED)));
    }

    @Test
    void publishesPageIndexedEventAfterUpsert() {
        Document afterSanitize = event().toDocument().withExtractedText("some page text here");
        ContentProcessor sanitizer = processorReturning(0, ProcessingResult.success(afterSanitize));
        when(repository.upsertWithVersioning(any(), any())).thenReturn(new UpsertOutcome(42L, 3, true));

        new IngestionPipeline(List.of(sanitizer), repository, events).process(event());

        verify(events).publishEvent(argThat((PageIndexedEvent e) ->
            e.pageId().equals(42L) && e.url().equals("http://example.onion")
                && e.version() == 3 && e.isNewVersion()));
    }

    @Test
    void doesNotPublishAnEventWhenHalted() {
        Document afterSanitize = event().toDocument().withExtractedText("blocked content");
        ContentProcessor sanitizer = processorReturning(0, ProcessingResult.success(afterSanitize));
        ContentProcessor guard = processorReturning(5, ProcessingResult.halt(afterSanitize, "url-denylist"));

        new IngestionPipeline(List.of(sanitizer, guard), repository, events).process(event());

        verifyNoInteractions(events);
    }

    @Test
    void failedEmbeddingOutcomeCarriesTheErrorMessage() {
        Document afterSanitize = event().toDocument().withExtractedText("some page text here");
        ContentProcessor sanitizer = processorReturning(0, ProcessingResult.success(afterSanitize));

        EmbeddingProcessor embedding = mock(EmbeddingProcessor.class);
        lenient().when(embedding.order()).thenReturn(100);
        lenient().when(embedding.supports(any())).thenReturn(true);
        lenient().when(embedding.process(any())).thenReturn(ProcessingResult.failed(afterSanitize, "ollama unreachable"));

        var pipeline = new IngestionPipeline(List.of(sanitizer, embedding), repository, events);
        pipeline.process(event());

        verify(repository).upsertWithVersioning(any(),
            argThat(outcome -> outcome != null
                && outcome.status().equals(EmbeddingOutcome.FAILED_TRANSIENT)
                && outcome.errorMessage().equals("ollama unreachable")));
    }
}
