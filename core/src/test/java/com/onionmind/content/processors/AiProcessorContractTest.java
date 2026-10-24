package com.onionmind.content.processors;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.TaskContext;
import com.onionmind.content.AiEnrichmentGate;
import com.onionmind.content.ContentProcessor;
import com.onionmind.content.Document;
import com.onionmind.content.DocumentType;
import com.onionmind.content.Enrichment;
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

    private static final int CAP = 8000;

    private Object[] lastCall;
    private final AIOrchestrator failing = mock(AIOrchestrator.class, invocation -> {
        lastCall = invocation.getArguments();
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

    private static final String AMBIGUOUS = "xyzzy plugh frobnicate wibble wobble flibbertigibbet quux "
        + "grault garply waldo fred plugh xyzzy thud spam ham eggs";

    Stream<Arguments> aiProcessors() {
        return processorsFor(AMBIGUOUS, "some body text");
    }

    private Stream<Arguments> processorsFor(String ambiguous, String body) {
        return Stream.of(
            arguments("language", new LanguageDetectorProcessor(failing, gate, CAP), doc(ambiguous, Enrichment.EMPTY)),
            arguments("summary", new SummarizerProcessor(failing, gate, CAP), doc(body, Enrichment.EMPTY)),
            arguments("classifier", new ClassifierProcessor(failing, gate, CAP), doc(body, Enrichment.EMPTY)));
    }

    /** 20K chars: the first CAP are ambiguous filler (so the language step asks the LLM), then a marker. */
    private static final String HUGE = (AMBIGUOUS + " ").repeat(CAP / AMBIGUOUS.length() + 1).substring(0, CAP)
        + " TAIL-MARKER " + "y".repeat(20_000 - CAP - 13);

    Stream<Arguments> hugeInputs() {
        return processorsFor(HUGE, HUGE);
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("hugeInputs")
    void llmSeesAtMostTheCapAndTokensAreEstimatedOnIt(String name, ContentProcessor processor, Document input) {
        when(gate.alreadyEnriched(any())).thenReturn(false);
        lastCall = null;

        processor.process(input);

        assertThat(HUGE).hasSize(20_000);
        String sent = (String) lastCall[0];
        TaskContext ctx = (TaskContext) lastCall[lastCall.length - 1];
        // the classifier prepends its taxonomy prompt, so check the page part, not the whole string
        assertThat(sent).endsWith(HUGE.substring(0, CAP)).doesNotContain("TAIL-MARKER");
        assertThat(ctx.approxTokens()).isEqualTo(CAP / 4);
    }
}
