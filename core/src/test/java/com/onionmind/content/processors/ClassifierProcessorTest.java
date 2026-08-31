package com.onionmind.content.processors;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Classification;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassifierProcessorTest {

    @Mock
    private AIOrchestrator orchestrator;
    @Mock
    private AiEnrichmentGate gate;

    private ClassifierProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ClassifierProcessor(orchestrator, gate);
    }

    private Document doc() {
        return new Document("http://x.onion", "tor", "<html>x</html>", "a page selling things", DocumentType.HTML);
    }

    @Test
    void acceptsACategoryInTheTaxonomy() {
        when(orchestrator.classify(any(), any())).thenReturn(new Classification("Marketplace", 0.8));

        ProcessingResult result = processor.process(doc());

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SUCCESS);
        assertThat(result.document().enrichment().category().category()).isEqualTo("marketplace");
    }

    @Test
    void collapsesACategoryOutsideTheTaxonomyToOther() {
        when(orchestrator.classify(any(), any())).thenReturn(new Classification("cryptocurrency-exchange", 0.9));

        ProcessingResult result = processor.process(doc());

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SUCCESS);
        assertThat(result.document().enrichment().category().category()).isEqualTo("other");
        assertThat(result.document().enrichment().category().confidence()).isLessThanOrEqualTo(0.5);
    }

    @Test
    void runsAtOrderForty() {
        assertThat(processor.order()).isEqualTo(40);
    }
}
