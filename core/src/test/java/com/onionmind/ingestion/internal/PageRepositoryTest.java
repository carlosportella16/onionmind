package com.onionmind.ingestion.internal;

import com.onionmind.TestcontainersConfiguration;
import com.onionmind.ai.Classification;
import com.onionmind.ai.Summary;
import com.onionmind.ai.Translation;
import com.onionmind.content.AiOutcome;
import com.onionmind.content.Document;
import com.onionmind.content.DocumentType;
import com.onionmind.content.EmbeddingOutcome;
import com.onionmind.content.Enrichment;
import com.onionmind.content.Enrichment.LanguageTag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PageRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private PageRepository repository;

    private Document doc(String url, String text) {
        return new Document(url, "tor", "<html>" + text + "</html>", text, DocumentType.HTML);
    }

    private Integer versionOf(String url) {
        return jdbc.queryForObject("SELECT version FROM pages WHERE url = ?", Integer.class, url);
    }

    private Integer archivedCountFor(String url) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM page_versions
            WHERE page_id = (SELECT id FROM pages WHERE url = ?)
            """, Integer.class, url);
    }

    private String embeddingStatusOf(String url) {
        return jdbc.queryForObject("SELECT embedding_status FROM pages WHERE url = ?", String.class, url);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        repository = new PageRepository(jdbc);
    }

    @Test
    void newPageIsInsertedWithVersion1() {
        String url = "http://new-" + System.nanoTime() + ".onion";

        repository.upsertWithVersioning(doc(url, "first content ever seen here"));

        assertThat(versionOf(url)).isEqualTo(1);
    }

    @Test
    void identicalContentOnlyTouchesLastSeenAt() {
        String url = "http://same-" + System.nanoTime() + ".onion";
        repository.upsertWithVersioning(doc(url, "unchanged content"));

        repository.upsertWithVersioning(doc(url, "unchanged content"));

        assertThat(versionOf(url)).isEqualTo(1);
        assertThat(archivedCountFor(url)).isEqualTo(0);
    }

    @Test
    void differentContentIncrementsVersionAndArchivesPrevious() {
        String url = "http://changed-" + System.nanoTime() + ".onion";
        repository.upsertWithVersioning(doc(url, "version one content"));

        repository.upsertWithVersioning(doc(url, "version two different content"));

        assertThat(versionOf(url)).isEqualTo(2);
        assertThat(archivedCountFor(url)).isEqualTo(1);
    }

    @Test
    void newPageWithoutEmbeddingOutcomeStaysAtDefaultPendingStatus() {
        String url = "http://no-embedding-" + System.nanoTime() + ".onion";

        repository.upsertWithVersioning(doc(url, "content with no embedding module in this build"));

        assertThat(embeddingStatusOf(url)).isEqualTo("pending");
    }

    @Test
    void embeddingOutcomeIsPersistedOnNewPage() {
        String url = "http://embedded-" + System.nanoTime() + ".onion";

        repository.upsertWithVersioning(doc(url, "content that got embedded"),
            new EmbeddingOutcome(EmbeddingOutcome.EMBEDDED, null));

        assertThat(embeddingStatusOf(url)).isEqualTo(EmbeddingOutcome.EMBEDDED);
    }

    @Test
    void failedEmbeddingOutcomeIsPersistedWithErrorMessage() {
        String url = "http://embed-failed-" + System.nanoTime() + ".onion";

        repository.upsertWithVersioning(doc(url, "content whose embedding failed"),
            new EmbeddingOutcome(EmbeddingOutcome.FAILED_TRANSIENT, "ollama unreachable"));

        assertThat(embeddingStatusOf(url)).isEqualTo(EmbeddingOutcome.FAILED_TRANSIENT);
        String errorMessage = jdbc.queryForObject(
            "SELECT embedding_error_message FROM pages WHERE url = ?", String.class, url);
        assertThat(errorMessage).isEqualTo("ollama unreachable");
    }

    @Test
    void quarantineInsertsAuditRowAndMinimalPageWhenNoneExists() {
        String url = "http://quarantine-new-" + System.nanoTime() + ".onion";

        repository.quarantine(doc(url, "blocked content"), "url-denylist");

        assertThat(jdbc.queryForObject(
            "SELECT reason FROM quarantined_pages WHERE url = ?", String.class, url))
            .isEqualTo("url-denylist");
        assertThat(jdbc.queryForMap("SELECT ai_status, extracted_text, raw_html FROM pages WHERE url = ?", url))
            .containsEntry("ai_status", "quarantined")
            .containsEntry("extracted_text", null)
            .containsEntry("raw_html", null);
    }

    @Test
    void updateAiFieldsWritesEnrichmentAsJsonbAndMarksProcessed() {
        String url = "http://ai-" + System.nanoTime() + ".onion";
        repository.upsertWithVersioning(doc(url, "the page content"));

        Enrichment enrichment = Enrichment.EMPTY
            .withLanguage(new LanguageTag("en", 0.97))
            .withSummary(new Summary("um resumo curto", 0.9))
            .withCategory(new Classification("forum", 0.82))
            .withTranslation(new Translation("o conteúdo da página", "en", 0.88));

        repository.updateAiFields(url, AiOutcome.processed(enrichment));

        var row = jdbc.queryForMap("""
            SELECT ai_status, ai_error_message,
                   summary->>'text' AS s, category->>'category' AS c,
                   language->>'code' AS l, translated_text->>'text' AS t
            FROM pages WHERE url = ?
            """, url);
        assertThat(row)
            .containsEntry("ai_status", "processed")
            .containsEntry("ai_error_message", null)
            .containsEntry("s", "um resumo curto")
            .containsEntry("c", "forum")
            .containsEntry("l", "en")
            .containsEntry("t", "o conteúdo da página");
    }

    @Test
    void updateAiFieldsPartialFailureKeepsWhatWasGeneratedAndFlagsRetry() {
        String url = "http://ai-partial-" + System.nanoTime() + ".onion";
        repository.upsertWithVersioning(doc(url, "the page content"));

        Enrichment partial = Enrichment.EMPTY.withSummary(new Summary("só o resumo saiu", 0.9));
        repository.updateAiFields(url, AiOutcome.partialFailure(partial));

        var row = jdbc.queryForMap(
            "SELECT ai_status, ai_error_message, summary->>'text' AS s, category FROM pages WHERE url = ?", url);
        assertThat(row).containsEntry("ai_status", "failed_transient").containsEntry("s", "só o resumo saiu");
        assertThat(row.get("ai_error_message")).asString().contains("backfill");
        assertThat(row.get("category")).isNull();
    }

    @Test
    void quarantineStripsContentFromAnExistingPage() {
        String url = "http://quarantine-existing-" + System.nanoTime() + ".onion";
        repository.upsertWithVersioning(doc(url, "content that was fine before"));

        repository.quarantine(doc(url, "content that was fine before"), "text-pattern:3");

        assertThat(jdbc.queryForMap("SELECT ai_status, extracted_text, raw_html FROM pages WHERE url = ?", url))
            .containsEntry("ai_status", "quarantined")
            .containsEntry("extracted_text", null)
            .containsEntry("raw_html", null);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM quarantined_pages WHERE url = ?", Integer.class, url)).isEqualTo(1);
    }
}
