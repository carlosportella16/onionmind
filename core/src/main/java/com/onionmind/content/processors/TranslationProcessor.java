package com.onionmind.content.processors;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.TaskContext;
import com.onionmind.ai.Translation;
import com.onionmind.content.AiEnrichmentGate;
import com.onionmind.content.Document;
import com.onionmind.content.Enrichment.LanguageTag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * order 20 — translates non-Portuguese pages to Portuguese. The translation is stored as
 * its own field; {@code extracted_text} keeps the original so search and re-embedding stay
 * on the source content (fase3-sdd 7.6).
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class TranslationProcessor extends AbstractAiProcessor {

    private static final String TARGET = "português";

    public TranslationProcessor(AIOrchestrator orchestrator, AiEnrichmentGate gate) {
        super(orchestrator, gate);
    }

    @Override
    protected Document enrich(Document document, String text) {
        LanguageTag language = document.enrichment().language();
        if (language != null && "pt".equals(language.code())) {
            return document; // already Portuguese, nothing to translate
        }

        Translation translation = orchestrator.translate(text, TARGET,
            TaskContext.batch(TaskContext.TaskType.TRANSLATE, approxTokens(text),
                language != null ? language.code() : null, false));
        return document.withEnrichment(document.enrichment().withTranslation(translation));
    }

    @Override
    public int order() {
        return 20;
    }
}
