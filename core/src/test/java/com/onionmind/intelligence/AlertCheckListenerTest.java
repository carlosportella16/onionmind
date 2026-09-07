package com.onionmind.intelligence;

import com.onionmind.ai.Entity;
import com.onionmind.graph.GraphStore;
import com.onionmind.ingestion.PageContentLookup;
import com.onionmind.ingestion.PageIndexedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertCheckListenerTest {

    @Mock
    private AlertRuleRepository ruleRepository;

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private PageContentLookup contentLookup;

    @Mock
    private GraphStore graphStore;

    private AlertCheckListener listener() {
        return new AlertCheckListener(ruleRepository, alertRepository, contentLookup, graphStore);
    }

    private PageIndexedEvent event() {
        return new PageIndexedEvent(1L, "http://example.onion", "hash1", 1, true);
    }

    @Test
    void keywordRuleMatchesCaseInsensitively() {
        when(ruleRepository.findActive()).thenReturn(
            List.of(new AlertRule(10L, AlertCriteriaType.KEYWORD, "bitcoin", true)));
        when(contentLookup.currentText("http://example.onion")).thenReturn(Optional.of("this page mentions BITCOIN"));

        listener().on(event());

        verify(alertRepository).record(10L, 1L, 1);
    }

    @Test
    void keywordRuleDoesNotMatchWhenAbsent() {
        when(ruleRepository.findActive()).thenReturn(
            List.of(new AlertRule(10L, AlertCriteriaType.KEYWORD, "bitcoin", true)));
        when(contentLookup.currentText("http://example.onion")).thenReturn(Optional.of("unrelated content"));

        listener().on(event());

        verify(alertRepository, never()).record(10L, 1L, 1);
    }

    @Test
    void categoryRuleMatchesTheAssignedCategory() {
        when(ruleRepository.findActive()).thenReturn(
            List.of(new AlertRule(11L, AlertCriteriaType.CATEGORY, "forum", true)));
        lenient().when(contentLookup.currentText(anyString())).thenReturn(Optional.of("some text"));
        when(contentLookup.currentCategory("http://example.onion")).thenReturn(Optional.of("forum"));

        listener().on(event());

        verify(alertRepository).record(11L, 1L, 1);
    }

    @Test
    void entityTypeRuleMatchesAnExtractedEntityType() {
        when(ruleRepository.findActive()).thenReturn(
            List.of(new AlertRule(12L, AlertCriteriaType.ENTITY_TYPE, "CRYPTO_WALLET", true)));
        lenient().when(contentLookup.currentText(anyString())).thenReturn(Optional.of("some text"));
        when(graphStore.findEntitiesByPage("http://example.onion"))
            .thenReturn(List.of(new Entity(Entity.EntityType.CRYPTO_WALLET, "1A2b3C", 0.9)));

        listener().on(event());

        verify(alertRepository).record(12L, 1L, 1);
    }

    @Test
    void noActiveRulesSkipsEverything() {
        when(ruleRepository.findActive()).thenReturn(List.of());

        listener().on(event());

        verify(contentLookup, never()).currentText(anyString());
        verifyNoInteractions(alertRepository);
    }

    @Test
    void failureInRuleEvaluationDoesNotPropagate() {
        when(ruleRepository.findActive()).thenThrow(new RuntimeException("db down"));

        listener().on(event()); // must not throw
    }
}
