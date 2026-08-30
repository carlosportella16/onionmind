package com.onionmind.content.processors;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Summary;
import com.onionmind.ai.Translation;
import com.onionmind.content.AiEnrichmentGate;
import com.onionmind.content.Document;
import com.onionmind.content.DocumentType;
import com.onionmind.content.Enrichment;
import com.onionmind.content.ProcessingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SummarizerProcessorTest {

    @Mock
    private AIOrchestrator orchestrator;
    @Mock
    private AiEnrichmentGate gate;

    private SummarizerProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SummarizerProcessor(orchestrator, gate);
    }

    private Document doc() {
        return new Document("http://x.onion", "tor", "<html>x</html>", "original english body", DocumentType.HTML);
    }

    @Test
    void summarizesOriginalTextWhenThereIsNoTranslation() {
        when(orchestrator.summarize(eq("original english body"), any()))
            .thenReturn(new Summary("resumo do conteúdo", 0.9));

        ProcessingResult result = processor.process(doc());

        assertThat(result.document().enrichment().summary().text()).isEqualTo("resumo do conteúdo");
        assertThat(result.document().enrichment().summary().confidence()).isEqualTo(0.9);
    }

    @Test
    void summarizesTheTranslationWhenPresent() {
        Document translated = doc().withEnrichment(
            Enrichment.EMPTY.withTranslation(new Translation("corpo traduzido", "en", 0.9)));
        when(orchestrator.summarize(eq("corpo traduzido"), any()))
            .thenReturn(new Summary("resumo da tradução", 0.88));

        ProcessingResult result = processor.process(translated);

        assertThat(result.document().enrichment().summary().text()).isEqualTo("resumo da tradução");
    }

    @Test
    void runsAtOrderThirty() {
        assertThat(processor.order()).isEqualTo(30);
    }
}
