package com.onionmind.content.processors;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.content.AiEnrichmentGate;
import com.onionmind.content.ContentProcessor;
import com.onionmind.content.Document;
import com.onionmind.content.DocumentType;
import com.onionmind.content.Enrichment;
import com.onionmind.content.Enrichment.LanguageTag;
import com.onionmind.content.ProcessingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contract every order 10-40 processor must honour (fase3-sdd 7.6): skip unchanged
 * content (content_hash gate) without spending quota, and never throw out of the pipeline
 * on an AI failure.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiProcessorContractTest {

    private final AIOrchestrator failing = mock(AIOrchestrator.class, invocation -> {
        throw new RuntimeException("all providers down");
    });
    private final AiEnrichmentGate gate = mock(AiEnrichmentGate.class);

    @BeforeEach
    void reset() {
        org.mockito.Mockito.reset(gate);
    }

    private Document doc(String text, Enrichment enrichment) {
        return new Document("http://x.onion", "tor", "<html>x</html>", text, DocumentType.HTML)
            .withEnrichment(enrichment);
    }

    Stream<Arguments> aiProcessors() {
        String ambiguous = "xyzzy plugh frobnicate wibble wobble flibbertigibbet quux "
            + "grault garply waldo fred plugh xyzzy thud spam ham eggs";
        return Stream.of(
            arguments("language", new LanguageDetectorProcessor(failing, gate), doc(ambiguous, Enrichment.EMPTY)),
            arguments("translation", new TranslationProcessor(failing, gate),
                doc("english body text", Enrichment.EMPTY.withLanguage(new LanguageTag("en", 0.9)))),
            arguments("summary", new SummarizerProcessor(failing, gate), doc("some body text", Enrichment.EMPTY)),
            arguments("classifier", new ClassifierProcessor(failing, gate), doc("some body text", Enrichment.EMPTY)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aiProcessors")
    void skipsWhenContentHashIsUnchanged(String name, ContentProcessor processor, Document input) {
        when(gate.alreadyEnriched(any())).thenReturn(true);

        assertThat(processor.process(input).status()).isEqualTo(ProcessingResult.Status.SKIPPED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aiProcessors")
    void aiFailureBecomesFailedNotAnException(String name, ContentProcessor processor, Document input) {
        when(gate.alreadyEnriched(any())).thenReturn(false);

        ProcessingResult result = processor.process(input);

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.FAILED);
    }
}
