package com.onionmind.ingestion;

import com.onionmind.content.ContentProcessor;
import com.onionmind.content.Document;
import com.onionmind.content.ProcessingResult;
import com.onionmind.ingestion.internal.PageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

        verify(repository).upsertWithVersioning(argThat(d -> d.extractedText().equals("final full text content here")));
    }

    @Test
    void failedProcessorDoesNotAbortPipeline_persistsPriorResult() {
        Document afterFirst = event().toDocument().withExtractedText("text from the first processor");

        ContentProcessor first = processorReturning(0, ProcessingResult.success(afterFirst));
        ContentProcessor second = processorReturning(1, ProcessingResult.failed(afterFirst, "boom"));

        var pipeline = new IngestionPipeline(List.of(first, second), repository);
        pipeline.process(event());

        verify(repository).upsertWithVersioning(argThat(d -> d.extractedText().equals("text from the first processor")));
    }

    @Test
    void documentWithoutExtractedTextIsNotPersisted() {
        ContentProcessor noText = processorReturning(0, ProcessingResult.skipped(event().toDocument(), "empty html"));

        var pipeline = new IngestionPipeline(List.of(noText), repository);
        pipeline.process(event());

        verifyNoInteractions(repository);
    }
}
