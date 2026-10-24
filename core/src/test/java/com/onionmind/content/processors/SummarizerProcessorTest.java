package com.onionmind.content.processors;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Summary;
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
import static org.mockito.ArgumentMatchers.argThat;
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
        processor = new SummarizerProcessor(orchestrator, gate, 8000);
    }

    private Document doc(String text, String language) {
        return new Document("http://x.onion", "tor", "<html>x</html>", text, DocumentType.HTML)
            .withEnrichment(Enrichment.EMPTY.withLanguage(new LanguageTag(language, 0.9)));
    }

    @Test
    void summarizesTheOriginalEnglishText() {
        when(orchestrator.summarize(eq("original english body"), any()))
            .thenReturn(new Summary("A summary of the content.", 0.9));

        ProcessingResult result = processor.process(doc("original english body", "en"));

        assertThat(result.document().enrichment().summary().text()).isEqualTo("A summary of the content.");
        assertThat(result.document().enrichment().summary().confidence()).isEqualTo(0.9);
    }

    @Test
    void summarizesTheOriginalPortugueseTextUntranslated() {
        when(orchestrator.summarize(eq("corpo original em português"), argThat(ctx -> "en".equals(ctx.sourceLanguage()))))
            .thenReturn(new Summary("A summary of the Portuguese page.", 0.88));

        ProcessingResult result = processor.process(doc("corpo original em português", "pt"));

        assertThat(result.document().enrichment().summary().text()).isEqualTo("A summary of the Portuguese page.");
    }

    @Test
    void runsAtOrderThirty() {
        assertThat(processor.order()).isEqualTo(30);
    }
}
