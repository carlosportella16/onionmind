package com.onionmind.content.processors;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Translation;
import com.onionmind.content.AiEnrichmentGate;
import com.onionmind.content.Document;
import com.onionmind.content.DocumentType;
import com.onionmind.content.Enrichment;
import com.onionmind.content.Enrichment.LanguageTag;
import com.onionmind.content.ProcessingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranslationProcessorTest {

    @Mock
    private AIOrchestrator orchestrator;
    @Mock
    private AiEnrichmentGate gate;

    private TranslationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new TranslationProcessor(orchestrator, gate);
    }

    private Document docWithLanguage(String code) {
        return new Document("http://x.onion", "tor", "<html>x</html>", "the page body text", DocumentType.HTML)
            .withEnrichment(Enrichment.EMPTY.withLanguage(new LanguageTag(code, 0.9)));
    }

    @Test
    void portuguesePageIsNotTranslated() {
        ProcessingResult result = processor.process(docWithLanguage("pt"));

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SUCCESS);
        assertThat(result.document().enrichment().translation()).isNull();
        verifyNoInteractions(orchestrator);
    }

    @Test
    void foreignPageIsTranslatedAndOriginalTextIsUntouched() {
        when(orchestrator.translate(eq("the page body text"), any(), any()))
            .thenReturn(new Translation("o corpo da página", "en", 0.93));

        ProcessingResult result = processor.process(docWithLanguage("en"));

        assertThat(result.document().enrichment().translation().text()).isEqualTo("o corpo da página");
        assertThat(result.document().extractedText()).isEqualTo("the page body text");
    }

    @Test
    void aiFailureDoesNotBlockThePipeline() {
        when(orchestrator.translate(any(), any(), any())).thenThrow(new RuntimeException("all providers down"));

        ProcessingResult result = processor.process(docWithLanguage("en"));

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.FAILED);
        assertThat(result.error()).contains("all providers down");
    }

    @Test
    void runsAtOrderTwenty() {
        assertThat(processor.order()).isEqualTo(20);
    }
}
