package com.onionmind.graph;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Entity;
import com.onionmind.ingestion.PageContentLookup;
import com.onionmind.ingestion.PageIndexedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntityExtractionListenerTest {

    @Mock
    private GraphStore graphStore;

    @Mock
    private PageContentLookup contentLookup;

    @Mock
    private AIOrchestrator orchestrator;

    private PageIndexedEvent event() {
        return new PageIndexedEvent(1L, "http://example.onion", "hash1", 1, true);
    }

    @Test
    void extractsAndPersistsEntitiesForNewContent() {
        when(graphStore.hasProcessedContent("http://example.onion", "hash1")).thenReturn(false);
        when(contentLookup.currentText("http://example.onion")).thenReturn(Optional.of("page text"));
        List<Entity> entities = List.of(new Entity(Entity.EntityType.PERSON, "Ana", 0.9));
        when(orchestrator.extractEntities(eq("page text"), any())).thenReturn(entities);

        new EntityExtractionListener(graphStore, contentLookup, orchestrator).on(event());

        verify(graphStore).upsertEntities("http://example.onion", "hash1", entities);
    }

    @Test
    void skipsExtractionWhenContentAlreadyProcessed() {
        when(graphStore.hasProcessedContent("http://example.onion", "hash1")).thenReturn(true);

        new EntityExtractionListener(graphStore, contentLookup, orchestrator).on(event());

        verify(orchestrator, never()).extractEntities(anyString(), any());
        verify(graphStore, never()).upsertEntities(anyString(), anyString(), any());
    }

    @Test
    void skipsExtractionWhenPageHasNoText() {
        when(graphStore.hasProcessedContent(anyString(), anyString())).thenReturn(false);
        when(contentLookup.currentText("http://example.onion")).thenReturn(Optional.empty());

        new EntityExtractionListener(graphStore, contentLookup, orchestrator).on(event());

        verify(orchestrator, never()).extractEntities(anyString(), any());
    }

    @Test
    void doesNotPropagateWhenExtractionFails() {
        lenient().when(graphStore.hasProcessedContent(anyString(), anyString())).thenReturn(false);
        lenient().when(contentLookup.currentText("http://example.onion")).thenReturn(Optional.of("page text"));
        when(orchestrator.extractEntities(anyString(), any())).thenThrow(new RuntimeException("all providers down"));

        var listener = new EntityExtractionListener(graphStore, contentLookup, orchestrator);
        listener.on(event()); // must not throw — a failure here must never look like an ingestion failure

        verify(graphStore, never()).upsertEntities(anyString(), anyString(), any());
    }

    @Test
    void doesNotPropagateWhenTheGraphStoreIsUnavailable() {
        when(graphStore.hasProcessedContent(anyString(), anyString()))
            .thenThrow(new RuntimeException("Neo4j unreachable"));

        var listener = new EntityExtractionListener(graphStore, contentLookup, orchestrator);
        listener.on(event()); // the page is already indexed and searchable — this must never look like an ingestion failure
    }
}
