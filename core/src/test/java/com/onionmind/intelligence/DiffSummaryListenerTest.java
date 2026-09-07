package com.onionmind.intelligence;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Summary;
import com.onionmind.ingestion.PageContentLookup;
import com.onionmind.ingestion.PageIndexedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiffSummaryListenerTest {

    @Mock
    private PageContentLookup contentLookup;

    @Mock
    private AIOrchestrator orchestrator;

    @Mock
    private PageDiffRepository repository;

    private DiffSummaryListener listener() {
        return new DiffSummaryListener(contentLookup, orchestrator, repository);
    }

    @Test
    void generatesAndSavesADiffOnANewVersion() {
        var event = new PageIndexedEvent(1L, "http://example.onion", "hash2", 2, true);
        when(contentLookup.previousVersionText("http://example.onion", 2)).thenReturn(Optional.of("old text"));
        when(contentLookup.currentText("http://example.onion")).thenReturn(Optional.of("new text"));
        Summary diff = new Summary("mudou X", 0.9);
        when(orchestrator.summarizeDiff(eq("old text"), eq("new text"), any())).thenReturn(diff);

        listener().on(event);

        verify(repository).save(1L, 1, 2, diff);
    }

    @Test
    void skipsTheFirstEverVersion() {
        var event = new PageIndexedEvent(1L, "http://example.onion", "hash1", 1, true);

        listener().on(event);

        verify(orchestrator, never()).summarizeDiff(anyString(), anyString(), any());
        verify(repository, never()).save(any(), anyInt(), anyInt(), any());
    }

    @Test
    void skipsWhenContentIsUnchanged() {
        var event = new PageIndexedEvent(1L, "http://example.onion", "hash1", 1, false);

        listener().on(event);

        verify(orchestrator, never()).summarizeDiff(anyString(), anyString(), any());
    }

    @Test
    void skipsWhenNoPreviousVersionIsArchived() {
        var event = new PageIndexedEvent(1L, "http://example.onion", "hash2", 2, true);
        when(contentLookup.previousVersionText("http://example.onion", 2)).thenReturn(Optional.empty());

        listener().on(event);

        verify(orchestrator, never()).summarizeDiff(anyString(), anyString(), any());
    }

    @Test
    void doesNotPropagateWhenDiffGenerationFails() {
        var event = new PageIndexedEvent(1L, "http://example.onion", "hash2", 2, true);
        lenient().when(contentLookup.previousVersionText(anyString(), eq(2))).thenReturn(Optional.of("old"));
        lenient().when(contentLookup.currentText(anyString())).thenReturn(Optional.of("new"));
        when(orchestrator.summarizeDiff(anyString(), anyString(), any()))
            .thenThrow(new RuntimeException("all providers down"));

        listener().on(event); // must not throw — versioning already committed by this point

        verify(repository, never()).save(any(), anyInt(), anyInt(), any());
    }
}
