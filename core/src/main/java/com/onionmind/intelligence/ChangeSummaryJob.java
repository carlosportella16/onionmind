package com.onionmind.intelligence;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Summary;
import com.onionmind.ai.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Summarizes non-trivial changes with the existing summarize operation (fase3-sdd 13). No
 * transaction around the LLM call — same rule as the AI backfill (fix-ingestion-stability):
 * a slow provider must never hold a DB connection.
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class ChangeSummaryJob {

    private static final Logger log = LoggerFactory.getLogger(ChangeSummaryJob.class);

    private final JdbcTemplate jdbc;
    private final AIOrchestrator orchestrator;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final int batchSize;
    private final int maxAttempts;

    ChangeSummaryJob(JdbcTemplate jdbc, AIOrchestrator orchestrator,
                     @Value("${intelligence.summary.batch-size:20}") int batchSize,
                     @Value("${intelligence.summary.max-attempts:3}") int maxAttempts) {
        this.jdbc = jdbc;
        this.orchestrator = orchestrator;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${intelligence.summary.interval-ms:300000}")
    public void run() {
        List<PendingChange> pending = jdbc.query("""
            SELECT id, url, from_version, to_version, added_text, removed_text,
                   entities_added::text AS added, entities_removed::text AS removed,
                   category_from, category_to, attempts
            FROM page_changes
            WHERE status IN ('pending', 'failed_transient')
            ORDER BY id
            LIMIT ?
            """, (rs, n) -> new PendingChange(rs.getLong("id"), rs.getString("url"), rs.getInt("from_version"),
            rs.getInt("to_version"), rs.getString("added_text"), rs.getString("removed_text"),
            rs.getString("added"), rs.getString("removed"), rs.getString("category_from"),
            rs.getString("category_to"), rs.getInt("attempts")), batchSize);

        for (PendingChange change : pending) {
            summarize(change);
        }
    }

    private void summarize(PendingChange change) {
        String text = describe(change);
        try {
            Summary summary = orchestrator.summarize(text,
                TaskContext.batch(TaskContext.TaskType.SUMMARIZE, text.length() / 4, "pt", false));
            jdbc.update("UPDATE page_changes SET status = 'summarized', summary = ?::jsonb, attempts = attempts + 1 WHERE id = ?",
                mapper.writeValueAsString(Map.of("text", summary.text(), "confidence", summary.confidence())),
                change.id());
        } catch (RuntimeException e) {
            String status = change.attempts() + 1 >= maxAttempts ? "failed_permanent" : "failed_transient";
            log.warn("Change summary for {} v{} failed ({}): {}", change.url(), change.toVersion(), status, e.getMessage());
            jdbc.update("UPDATE page_changes SET status = ?, attempts = attempts + 1 WHERE id = ?", status, change.id());
        }
    }

    /** What the summarizer reads: a structured description of the diff, not the page. */
    String describe(PendingChange c) {
        StringBuilder sb = new StringBuilder()
            .append("Resuma em 1 a 3 frases o que mudou nesta página entre a versão ")
            .append(c.fromVersion()).append(" e a ").append(c.toVersion()).append(" (").append(c.url()).append(").\n");
        if (c.categoryFrom() != null && !c.categoryFrom().equals(c.categoryTo())) {
            sb.append("Categoria: de ").append(c.categoryFrom()).append(" para ").append(c.categoryTo()).append(".\n");
        }
        appendEntities(sb, "Entidades novas", c.entitiesAdded());
        appendEntities(sb, "Entidades que sumiram", c.entitiesRemoved());
        appendText(sb, "Trechos adicionados", c.addedText());
        appendText(sb, "Trechos removidos", c.removedText());
        return sb.toString();
    }

    private void appendEntities(StringBuilder sb, String label, String json) {
        if (json == null) {
            return;
        }
        String list = mapper.readTree(json).valueStream()
            .map((JsonNode e) -> e.path("display").asString() + " (" + e.path("type").asString() + ")")
            .collect(Collectors.joining(", "));
        if (!list.isEmpty()) {
            sb.append(label).append(": ").append(list).append(".\n");
        }
    }

    private static void appendText(StringBuilder sb, String label, String text) {
        if (text != null && !text.isBlank()) {
            sb.append(label).append(":\n").append(text).append('\n');
        }
    }

    record PendingChange(long id, String url, int fromVersion, int toVersion, String addedText, String removedText,
                         String entitiesAdded, String entitiesRemoved, String categoryFrom, String categoryTo,
                         int attempts) {}
}
