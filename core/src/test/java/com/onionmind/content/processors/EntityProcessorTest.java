package com.onionmind.content.processors;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.AllProvidersExhaustedException;
import com.onionmind.ai.EntityExtraction;
import com.onionmind.ai.NamedEntity;
import com.onionmind.ai.TaskContext;
import com.onionmind.content.AiEnrichmentGate;
import com.onionmind.content.Document;
import com.onionmind.content.DocumentType;
import com.onionmind.content.ExtractedEntity;
import com.onionmind.content.ProcessingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntityProcessorTest {

    private static final String WALLET = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa";

    @Mock
    private AIOrchestrator orchestrator;
    @Mock
    private AiEnrichmentGate gate;

    private EntityProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new EntityProcessor(orchestrator, gate, 30, 200);
    }

    private Document doc(String text) {
        return new Document("http://loja.onion/", "tor", "<html/>", text, DocumentType.HTML);
    }

    @Test
    void combinesPatternAndNamedEntities() {
        when(orchestrator.extractEntities(anyString(), any())).thenReturn(new EntityExtraction(List.of(
            new NamedEntity("person", "José Silva", 0.9),
            new NamedEntity("organization", "Mercado Sombrio", 0.8)), 0.9));

        ProcessingResult result = processor.process(doc("Fale com José Silva. Pague em " + WALLET));

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SUCCESS);
        assertThat(result.document().enrichment().entities()).extracting(ExtractedEntity::id)
            .containsExactly("crypto_wallet:" + WALLET, "person:jose silva", "organization:mercado sombrio");
    }

    @Test
    void llmFailureKeepsPatternEntitiesAndStillFailsTheStep() {
        when(orchestrator.extractEntities(anyString(), any()))
            .thenThrow(new AllProvidersExhaustedException("all providers failed", null));

        ProcessingResult result = processor.process(doc("Pague em " + WALLET));

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.FAILED);
        assertThat(result.document().enrichment().entities()).extracting(ExtractedEntity::key).containsExactly(WALLET);
    }

    @Test
    void noProviderWithQuotaWaitsInsteadOfKeepingAPartialResult() {
        when(orchestrator.extractEntities(anyString(), any()))
            .thenThrow(new AllProvidersExhaustedException("no AI provider with quota", true, null));

        assertThat(processor.process(doc("Pague em " + WALLET)).status()).isEqualTo(ProcessingResult.Status.WAITING);
    }

    @Test
    void sendsAtMostMaxInputCharsToTheLlmButPatternsSeeEverything() {
        when(orchestrator.extractEntities(anyString(), any())).thenReturn(new EntityExtraction(List.of(), 0.9));
        String text = "x".repeat(100) + " " + WALLET;

        ProcessingResult result = processor.process(doc(text));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<TaskContext> ctx = ArgumentCaptor.forClass(TaskContext.class);
        verify(orchestrator).extractEntities(sent.capture(), ctx.capture());
        assertThat(sent.getValue()).hasSize(30);
        assertThat(ctx.getValue().type()).isEqualTo(TaskContext.TaskType.EXTRACT_ENTITIES);
        assertThat(result.document().enrichment().entities()).extracting(ExtractedEntity::key).containsExactly(WALLET);
    }

    @Test
    void unchangedContentSpendsNoQuota() {
        when(gate.alreadyEnriched(any())).thenReturn(true);

        ProcessingResult result = processor.process(doc("Pague em " + WALLET));

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SKIPPED);
        verify(orchestrator, never()).extractEntities(anyString(), any());
    }

    @Test
    void capsEntitiesPerPage() {
        processor = new EntityProcessor(orchestrator, gate, 8000, 1);
        when(orchestrator.extractEntities(anyString(), any())).thenReturn(new EntityExtraction(List.of(
            new NamedEntity("person", "Ana", 0.9), new NamedEntity("person", "Bia", 0.9)), 0.9));

        assertThat(processor.process(doc("Ana e Bia")).document().enrichment().entities()).hasSize(1);
    }

    @Test
    void runsAtOrderFifty() {
        assertThat(processor.order()).isEqualTo(50);
    }
}
