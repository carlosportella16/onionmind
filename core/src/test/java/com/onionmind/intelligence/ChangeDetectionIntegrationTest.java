package com.onionmind.intelligence;

import com.onionmind.TestcontainersConfiguration;
import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Summary;
import com.onionmind.content.EntityType;
import com.onionmind.content.ExtractedEntity;
import com.onionmind.ingestion.PageEnrichedEvent;
import com.onionmind.ingestion.PageQuarantinedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Listener, summary job and query against real Postgres (V7 schema). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ChangeDetectionIntegrationTest {

    private static final ExtractedEntity WALLET =
        ExtractedEntity.of(EntityType.CRYPTO_WALLET, "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", 1.0);
    private static final ExtractedEntity ANA = ExtractedEntity.of(EntityType.PERSON, "Ana", 0.9);

    @Autowired
    private JdbcTemplate jdbc;

    private ChangeDetectionListener listener;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM page_changes");
        jdbc.update("DELETE FROM page_versions");
        jdbc.update("DELETE FROM pages");
        listener = new ChangeDetectionListener(jdbc, 40, 4000);
    }

    private long page(String url, int version, String text) {
        jdbc.update("""
            INSERT INTO pages (url, source_type, extracted_text, content_hash, version, ai_status)
            VALUES (?, 'tor', ?, ?, ?, 'processed')
            """, url, text, "h" + version, version);
        return jdbc.queryForObject("SELECT id FROM pages WHERE url = ?", Long.class, url);
    }

    private void version(long pageId, int version, String text, String category, String entitiesJson) {
        jdbc.update("""
            INSERT INTO page_versions (page_id, version, content_hash, extracted_text, category, entities)
            VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """, pageId, version, "h" + version, text,
            category == null ? null : "{\"category\":\"" + category + "\",\"confidence\":0.9}", entitiesJson);
    }

    private static PageEnrichedEvent event(long id, String url, int version, String category, ExtractedEntity... e) {
        return new PageEnrichedEvent(id, url, "x.onion", version, "h" + version, category, 0.9, List.of(e));
    }

    private Map<String, Object> change(String url) {
        return jdbc.queryForMap("""
            SELECT from_version, to_version, status, added_text, removed_text, category_from, category_to,
                   entities_added::text AS added, entities_removed::text AS removed
            FROM page_changes WHERE url = ?
            """, url);
    }

    @Test
    void newParagraphWithNewWalletIsARealChange() {
        long id = page("http://m.onion/", 2, "Livros raros. Pague em " + WALLET.key() + ".");
        version(id, 1, "Livros raros.", "marketplace", "[]");

        listener.on(event(id, "http://m.onion/", 2, "marketplace", WALLET));

        var c = change("http://m.onion/");
        assertThat(c).containsEntry("from_version", 1).containsEntry("to_version", 2)
            .containsEntry("status", "pending").containsEntry("added_text", "Pague em " + WALLET.key() + ".")
            .containsEntry("removed_text", "");
        assertThat((String) c.get("added")).contains(WALLET.key());
    }

    @Test
    void counterOnlyChangeIsTrivial() {
        long id = page("http://f.onion/", 2, "Fórum aberto. 1524 visitas.");
        version(id, 1, "Fórum aberto. 1523 visitas.", "forum", "[]");

        listener.on(event(id, "http://f.onion/", 2, "forum"));

        assertThat(change("http://f.onion/")).containsEntry("status", "trivial");
    }

    @Test
    void categoryChangeIsNeverTrivial() {
        long id = page("http://c.onion/", 2, "Mesmo texto.");
        version(id, 1, "Mesmo texto.", "blog", "[]");

        listener.on(event(id, "http://c.onion/", 2, "forum"));

        assertThat(change("http://c.onion/")).containsEntry("status", "pending")
            .containsEntry("category_from", "blog").containsEntry("category_to", "forum");
    }

    @Test
    void baselineIsTheLastEnrichedVersionNotTheLastOne() {
        long id = page("http://b.onion/", 3, "Ana vende. Texto três.");
        version(id, 1, "Ana vende. Texto um.", "forum",
            "[{\"type\":\"person\",\"key\":\"ana\",\"display\":\"Ana\",\"confidence\":0.9},"
                + "{\"type\":\"crypto_wallet\",\"key\":\"" + WALLET.key() + "\",\"display\":\"w\",\"confidence\":1}]");
        version(id, 2, "Ana vende. Texto dois.", null, null); // changed again before being enriched

        listener.on(event(id, "http://b.onion/", 3, "forum", ANA));

        var c = change("http://b.onion/");
        assertThat(c).containsEntry("from_version", 1).containsEntry("removed_text", "Texto um.");
        assertThat((String) c.get("removed")).contains(WALLET.key()).doesNotContain("ana");
        assertThat((String) c.get("added")).isEqualTo("[]");
    }

    @Test
    void withoutAnyEnrichedSnapshotTheDiffIsTextOnly() {
        long id = page("http://t.onion/", 2, "Texto novo bem diferente do anterior, com muito mais conteúdo.");
        version(id, 1, "Texto velho.", null, null);

        listener.on(event(id, "http://t.onion/", 2, "forum", ANA));

        assertThat(change("http://t.onion/")).containsEntry("added", "[]").containsEntry("removed", "[]")
            .containsEntry("category_from", null).containsEntry("status", "pending");
    }

    @Test
    void duplicateEventRecordsOneChangeAndVersionOneIsIgnored() {
        long id = page("http://d.onion/", 2, "Texto novo bem diferente do anterior, com muito mais conteúdo.");
        version(id, 1, "Texto velho.", "forum", "[]");

        listener.on(event(id, "http://d.onion/", 2, "forum"));
        listener.on(event(id, "http://d.onion/", 2, "forum"));
        listener.on(event(id, "http://d.onion/", 1, "forum"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM page_changes", Integer.class)).isEqualTo(1);
    }

    @Test
    void quarantineErasesThePageChanges() {
        long id = page("http://q.onion/", 2, "Texto novo bem diferente do anterior, com muito mais conteúdo.");
        version(id, 1, "Texto velho.", "forum", "[]");
        listener.on(event(id, "http://q.onion/", 2, "forum"));

        listener.on(new PageQuarantinedEvent("http://q.onion/", "q.onion"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM page_changes", Integer.class)).isZero();
    }

    @Test
    void summaryJobSummarizesRetriesAndGivesUp() {
        long id = page("http://s.onion/", 2, "Agora aceitamos Monero e entregamos em todo o país.");
        version(id, 1, "Livros raros.", "marketplace", "[]");
        listener.on(event(id, "http://s.onion/", 2, "marketplace", WALLET));
        AIOrchestrator ai = mock(AIOrchestrator.class);
        when(ai.summarize(anyString(), any()))
            .thenThrow(new RuntimeException("providers down"))
            .thenReturn(new Summary("The marketplace now accepts Monero.", 0.88));
        ChangeSummaryJob job = new ChangeSummaryJob(jdbc, ai, 10, 3);

        job.run();
        assertThat(change("http://s.onion/")).containsEntry("status", "failed_transient");
        job.run();

        var row = jdbc.queryForMap("SELECT status, attempts, summary->>'text' AS s FROM page_changes");
        assertThat(row).containsEntry("status", "summarized").containsEntry("attempts", 2)
            .containsEntry("s", "The marketplace now accepts Monero.");
        verify(ai, org.mockito.Mockito.atLeastOnce()).summarize(anyString(),
            org.mockito.ArgumentMatchers.argThat(ctx -> "en".equals(ctx.sourceLanguage())));

        jdbc.update("UPDATE page_changes SET status = 'pending', attempts = 2");
        when(ai.summarize(anyString(), any())).thenThrow(new RuntimeException("still down"));
        job.run();
        assertThat(change("http://s.onion/")).containsEntry("status", "failed_permanent");
        job.run(); // no longer picked up
        verify(ai, org.mockito.Mockito.times(3)).summarize(anyString(), any());
    }

    @Test
    void summaryInputDescribesTheDiffNotThePage() {
        var job = new ChangeSummaryJob(jdbc, mock(AIOrchestrator.class), 10, 3);
        String text = job.describe(new ChangeSummaryJob.PendingChange(1, "http://m.onion/", 1, 2,
            "Agora aceitamos Monero.", "", "[{\"type\":\"crypto_wallet\",\"key\":\"1a\",\"display\":\"1A\"}]",
            "[]", "blog", "marketplace", 0));

        assertThat(text).startsWith("Summarize in 1 to 3 sentences what changed")
            .contains("version 1 and 2", "Category: from blog to marketplace", "New entities: 1A (crypto_wallet)",
                "Added text:\nAgora aceitamos Monero.")
            .doesNotContain("Removed text", "Removed entities", "Resuma");
    }

    @Test
    void trivialChangesNeverReachTheSummarizer() {
        long id = page("http://f.onion/", 2, "Fórum aberto. 1524 visitas.");
        version(id, 1, "Fórum aberto. 1523 visitas.", "forum", "[]");
        listener.on(event(id, "http://f.onion/", 2, "forum"));
        AIOrchestrator ai = mock(AIOrchestrator.class);

        new ChangeSummaryJob(jdbc, ai, 10, 3).run();

        verify(ai, never()).summarize(anyString(), any());
    }

    @Test
    void queryFiltersByCategoryAndTimeAndHidesTrivialChanges() throws Exception {
        Instant now = Instant.parse("2026-10-21T12:00:00Z");
        long f1 = page("http://forum1.onion/", 2, "x");
        long f2 = page("http://forum2.onion/", 2, "x");
        long m = page("http://market.onion/", 2, "x");
        long old = page("http://old.onion/", 2, "x");
        insertChange(f1, "http://forum1.onion/", "forum", "summarized", "2026-10-21T10:00:00Z", "Novo tópico sobre Monero.");
        insertChange(f2, "http://forum2.onion/", "forum", "trivial", "2026-10-21T11:00:00Z", null);
        insertChange(m, "http://market.onion/", "marketplace", "pending", "2026-10-21T09:00:00Z", null);
        insertChange(old, "http://old.onion/", "forum", "summarized", "2026-10-19T09:00:00Z", "Antigo.");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ChangesController(jdbc, Clock.fixed(now, ZoneOffset.UTC))).build();

        mvc.perform(get("/api/changes").param("category", "forum").param("since", "2026-10-20T12:00:00Z"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.changes.length()").value(1))
            .andExpect(jsonPath("$.changes[0].url").value("http://forum1.onion/"))
            .andExpect(jsonPath("$.changes[0].summary.text").value("Novo tópico sobre Monero."));

        mvc.perform(get("/api/changes")) // default: last 24h, every category
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.changes[0].url").value("http://forum1.onion/"))
            .andExpect(jsonPath("$.changes[1].status").value("pending"))
            .andExpect(jsonPath("$.changes[1].summary").doesNotExist());

        mvc.perform(get("/api/changes").param("limit", "1"))
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.changes.length()").value(1));
    }

    private void insertChange(long pageId, String url, String category, String status, String at, String summary) {
        jdbc.update("""
            INSERT INTO page_changes (page_id, url, from_version, to_version, category_to, status, detected_at, summary)
            VALUES (?, ?, 1, 2, ?, ?, ?::timestamptz, ?::jsonb)
            """, pageId, url, category, status, at,
            summary == null ? null : "{\"text\":\"" + summary + "\",\"confidence\":0.9}");
    }
}
