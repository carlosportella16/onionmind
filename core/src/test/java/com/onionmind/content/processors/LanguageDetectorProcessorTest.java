package com.onionmind.content.processors;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.LanguageDetection;
import com.onionmind.content.AiEnrichmentGate;
import com.onionmind.content.Document;
import com.onionmind.content.DocumentType;
import com.onionmind.content.ProcessingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LanguageDetectorProcessorTest {

    @Mock
    private AIOrchestrator orchestrator;
    @Mock
    private AiEnrichmentGate gate;

    private LanguageDetectorProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new LanguageDetectorProcessor(orchestrator, gate);
    }

    private Document doc(String text) {
        return new Document("http://x.onion", "tor", "<html>x</html>", text, DocumentType.HTML);
    }

    @Test
    void confidentHeuristicResultDoesNotCallTheLlm() {
        Document input = doc("Este é um fórum sobre moeda digital e privacidade na rede, "
            + "onde os usuários trocam informações e não revelam a identidade para mais ninguém.");

        ProcessingResult result = processor.process(input);

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SUCCESS);
        assertThat(result.document().enrichment().language().code()).isEqualTo("pt");
        verifyNoInteractions(orchestrator);
    }

    @Test
    void ambiguousTextEscalatesToTheLlm() {
        when(orchestrator.detectLanguage(any(), any())).thenReturn(new LanguageDetection("ru", 0.82));
        Document input = doc("xyzzy plugh frobnicate wibble wobble flibbertigibbet quux "
            + "grault garply waldo fred plugh xyzzy thud spam ham eggs");

        ProcessingResult result = processor.process(input);

        assertThat(result.document().enrichment().language().code()).isEqualTo("ru");
        assertThat(result.document().enrichment().language().confidence()).isEqualTo(0.82);
    }

    @Test
    void gateShortCircuitsBeforeAnyWork() {
        when(gate.alreadyEnriched(any())).thenReturn(true);

        ProcessingResult result = processor.process(doc("qualquer texto aqui que seja suficientemente longo para analise"));

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SKIPPED);
        verifyNoInteractions(orchestrator);
    }

    @Test
    void runsAtOrderTen() {
        assertThat(processor.order()).isEqualTo(10);
        assertThat(processor.supports(DocumentType.HTML)).isTrue();
    }
}
