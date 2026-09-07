package com.onionmind.intelligence;

import com.onionmind.graph.GraphStore;
import com.onionmind.ingestion.PageContentLookup;
import com.onionmind.ingestion.PageIndexedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Independent of {@link DiffSummaryListener} — both listen to the same event, neither knows the
 * other exists, a failure in one never blocks the other (Requirement "Avaliação de alerta
 * independente de outros processamentos", spec {@code intelligence-alerts}).
 */
@Component
@ConditionalOnProperty(prefix = "intelligence", name = "enabled", havingValue = "true")
class AlertCheckListener {

    private static final Logger log = LoggerFactory.getLogger(AlertCheckListener.class);

    private final AlertRuleRepository ruleRepository;
    private final AlertRepository alertRepository;
    private final PageContentLookup contentLookup;
    private final GraphStore graphStore;

    AlertCheckListener(AlertRuleRepository ruleRepository, AlertRepository alertRepository,
                        PageContentLookup contentLookup, GraphStore graphStore) {
        this.ruleRepository = ruleRepository;
        this.alertRepository = alertRepository;
        this.contentLookup = contentLookup;
        this.graphStore = graphStore;
    }

    @ApplicationModuleListener
    void on(PageIndexedEvent event) {
        try {
            List<AlertRule> rules = ruleRepository.findActive();
            if (rules.isEmpty()) {
                return;
            }
            Optional<String> text = contentLookup.currentText(event.url());
            for (AlertRule rule : rules) {
                if (matches(rule, event, text)) {
                    alertRepository.record(rule.id(), event.pageId(), event.version());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Alert evaluation failed for {}: {}", event.url(), e.getMessage());
        }
    }

    private boolean matches(AlertRule rule, PageIndexedEvent event, Optional<String> text) {
        return switch (rule.criteriaType()) {
            case KEYWORD -> text.isPresent()
                && text.get().toLowerCase(Locale.ROOT).contains(rule.criteriaValue().toLowerCase(Locale.ROOT));
            case CATEGORY -> contentLookup.currentCategory(event.url())
                .map(category -> category.equalsIgnoreCase(rule.criteriaValue()))
                .orElse(false);
            case ENTITY_TYPE -> graphStore.findEntitiesByPage(event.url()).stream()
                .anyMatch(entity -> entity.type().name().equalsIgnoreCase(rule.criteriaValue()));
        };
    }
}
