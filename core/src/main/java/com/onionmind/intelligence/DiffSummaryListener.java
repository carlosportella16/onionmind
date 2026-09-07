package com.onionmind.intelligence;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Summary;
import com.onionmind.ai.TaskContext;
import com.onionmind.ingestion.PageContentLookup;
import com.onionmind.ingestion.PageIndexedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Only reacts when a page has an actual previous version to diff against — the first version
 * ever, or a re-crawl with unchanged content, has nothing to summarize a change from
 * (spec `version-diff`, "Primeira versão de uma página" / content_hash-unchanged scenarios).
 */
@Component
@ConditionalOnProperty(prefix = "intelligence", name = "enabled", havingValue = "true")
class DiffSummaryListener {

    private static final Logger log = LoggerFactory.getLogger(DiffSummaryListener.class);

    private final PageContentLookup contentLookup;
    private final AIOrchestrator orchestrator;
    private final PageDiffRepository repository;

    DiffSummaryListener(PageContentLookup contentLookup, AIOrchestrator orchestrator, PageDiffRepository repository) {
        this.contentLookup = contentLookup;
        this.orchestrator = orchestrator;
        this.repository = repository;
    }

    @ApplicationModuleListener
    void on(PageIndexedEvent event) {
        if (!event.isNewVersion() || event.version() <= 1) {
            return;
        }
        try {
            Optional<String> previousText = contentLookup.previousVersionText(event.url(), event.version());
            Optional<String> currentText = contentLookup.currentText(event.url());
            if (previousText.isEmpty() || currentText.isEmpty()) {
                return;
            }

            int approxTokens = (previousText.get().length() + currentText.get().length()) / 4;
            Summary diff = orchestrator.summarizeDiff(previousText.get(), currentText.get(),
                TaskContext.batch(TaskContext.TaskType.SUMMARIZE, approxTokens, null, false));

            repository.save(event.pageId(), event.version() - 1, event.version(), diff);
        } catch (RuntimeException e) {
            // The version is already persisted (this event fires after upsertWithVersioning) —
            // a diff-generation failure must never look like a versioning/indexing failure.
            log.warn("Diff summary failed for {} v{}: {}", event.url(), event.version(), e.getMessage());
        }
    }
}
