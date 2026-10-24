package com.onionmind.ingestion.internal;

import com.onionmind.TestcontainersConfiguration;
import com.onionmind.ai.Classification;
import com.onionmind.ai.Summary;
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
    void changedContentResetsAiAndEmbeddingStatusToPending() {
        String url = "http://recrawled-" + System.nanoTime() + ".onion";
        repository.upsertWithVersioning(doc(url, "version one content"),
            new EmbeddingOutcome(EmbeddingOutcome.EMBEDDED, null));
        repository.updateAiFields(url, AiOutcome.processed(new Enrichment(
            new LanguageTag("pt", 1.0), new Summary("resumo", 0.9),
            new Classification("blog", 0.9))));

        repository.upsertWithVersioning(doc(url, "version two, different content"));

        var row = jdbc.queryForMap("SELECT ai_status, embedding_status FROM pages WHERE url = ?", url);
        assertThat(row).containsEntry("ai_status", "pending").containsEntry("embedding_status", "pending");
    }

    private java.util.Map<String, Object> aiStateOf(String url) {
        return jdbc.queryForMap("SELECT ai_status, ai_attempts FROM pages WHERE url = ?", url);
    }

    @Test
    void failedEnrichmentCountsAnAttemptAndSuccessDoesNot() {
        String url = "http://attempts-" + System.nanoTime() + ".onion";
        repository.upsertWithVersioning(doc(url, "some content"));

        repository.updateAiFields(url, AiOutcome.partialFailure(Enrichment.EMPTY));
        repository.updateAiFields(url, AiOutcome.partialFailure(Enrichment.EMPTY));
        assertThat(aiStateOf(url)).containsEntry("ai_status", "failed_transient").containsEntry("ai_attempts", 2);

        repository.updateAiFields(url, AiOutcome.processed(Enrichment.EMPTY));
        assertThat(aiStateOf(url)).containsEntry("ai_status", "processed").containsEntry("ai_attempts", 2);
    }

    @Test
    void permanentFailureWithNewContentGoesBackToPendingWithZeroAttempts() {
        String url = "http://permanent-new-" + System.nanoTime() + ".onion";
        repository.upsertWithVersioning(doc(url, "version one content"));
        repository.updateAiFields(url, AiOutcome.permanentFailure(Enrichment.EMPTY));

        repository.upsertWithVersioning(doc(url, "version two, different content"));

        assertThat(aiStateOf(url)).containsEntry("ai_status", "pending").containsEntry("ai_attempts", 0);
    }

    @Test
    void permanentFailureWithSameContentStaysPermanent() {
        String url = "http://permanent-same-" + System.nanoTime() + ".onion";
        repository.upsertWithVersioning(doc(url, "unchanged content"));
        repository.updateAiFields(url, AiOutcome.permanentFailure(Enrichment.EMPTY));

        repository.upsertWithVersioning(doc(url, "unchanged content"));

        assertThat(aiStateOf(url)).containsEntry("ai_status", "failed_permanent").containsEntry("ai_attempts", 1);
    }

    @Test
    void oversizedExtractedTextStillInsertsWithoutFailing() {
        // fix-large-page-backfill-limits: text past Postgres's ~1MB tsvector limit used to
        // fail the whole INSERT (search_vector's generated expression is now capped at
        // 500,000 chars — V6 migration).
        String url = "http://oversized-" + System.nanoTime() + ".onion";
        String bigText = "conteudo real de pagina grande de verdade ".repeat(40000); // ~1.7MB

        repository.upsertWithVersioning(doc(url, bigText));

        assertThat(jdbc.queryForObject(
            "SELECT length(extracted_text) FROM pages WHERE url = ?", Integer.class, url))
            .isEqualTo(bigText.length());
    }

    @Test
    void oversizedPageIsFindableByATermWithinTheIndexedPrefixAndKeepsFullText() {
        String url = "http://oversized-search-" + System.nanoTime() + ".onion";
        String earlyMarker = "termoinicialunico";
        String lateMarker = "termofinalunico";
        String filler = "conteudo de preenchimento repetido para estourar o limite do tsvector ";
        String bigText = earlyMarker + " " + filler.repeat(40000) + " " + lateMarker; // ~2.9MB

        repository.upsertWithVersioning(doc(url, bigText));

        Boolean foundByEarlyTerm = jdbc.queryForObject(
            "SELECT search_vector @@ websearch_to_tsquery('simple', ?) FROM pages WHERE url = ?",
            Boolean.class, earlyMarker, url);
        assertThat(foundByEarlyTerm).as("term within the indexed 500,000-char prefix").isTrue();

        assertThat(jdbc.queryForObject(
            "SELECT length(extracted_text) FROM pages WHERE url = ?", Integer.class, url))
            .as("full extracted_text is stored, not truncated")
            .isEqualTo(bigText.length());
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
            new EmbeddingOutcome(EmbeddingOutcome.FAILED_TRANSIENT, "gemini unreachable"));

        assertThat(embeddingStatusOf(url)).isEqualTo(EmbeddingOutcome.FAILED_TRANSIENT);
        String errorMessage = jdbc.queryForObject(
            "SELECT embedding_error_message FROM pages WHERE url = ?", String.class, url);
        assertThat(errorMessage).isEqualTo("gemini unreachable");
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
            .withCategory(new Classification("forum", 0.82));

        repository.updateAiFields(url, AiOutcome.processed(enrichment));

        var row = jdbc.queryForMap("""
            SELECT ai_status, ai_error_message,
                   summary->>'text' AS s, category->>'category' AS c,
                   language->>'code' AS l
            FROM pages WHERE url = ?
            """, url);
        assertThat(row)
            .containsEntry("ai_status", "processed")
            .containsEntry("ai_error_message", null)
            .containsEntry("s", "um resumo curto")
            .containsEntry("c", "forum")
            .containsEntry("l", "en");
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

    @Test
    void archivedVersionKeepsTheEnrichmentThatBelongedToIt() {
        String url = "http://snapshot-" + System.nanoTime() + ".onion";
        repository.upsertWithVersioning(doc(url, "version one content"));
        repository.updateAiFields(url, AiOutcome.processed(Enrichment.EMPTY
            .withSummary(new Summary("resumo v1", 0.9))
            .withCategory(new Classification("forum", 0.8))
            .withEntities(java.util.List.of(com.onionmind.content.ExtractedEntity.of(
                com.onionmind.content.EntityType.PERSON, "Ana", 0.9)))));

        repository.upsertWithVersioning(doc(url, "version two content"));

        var v1 = jdbc.queryForMap("""
            SELECT extracted_text, summary->>'text' AS summary, category->>'category' AS category,
                   entities->0->>'key' AS entity
            FROM page_versions WHERE page_id = (SELECT id FROM pages WHERE url = ?) AND version = 1
            """, url);
        assertThat(v1).containsEntry("extracted_text", "version one content").containsEntry("summary", "resumo v1")
            .containsEntry("category", "forum").containsEntry("entity", "ana");
    }

    @Test
    void versionNeverEnrichedIsArchivedWithoutEnrichment() {
        String url = "http://snapshot-pending-" + System.nanoTime() + ".onion";
        repository.upsertWithVersioning(doc(url, "version one content"));
        repository.updateAiFields(url, AiOutcome.processed(Enrichment.EMPTY.withSummary(new Summary("resumo v1", 0.9))));
        repository.upsertWithVersioning(doc(url, "version two content")); // v2 pending, still has v1's summary

        repository.upsertWithVersioning(doc(url, "version three content"));

        var v2 = jdbc.queryForMap("""
            SELECT extracted_text, summary FROM page_versions
            WHERE page_id = (SELECT id FROM pages WHERE url = ?) AND version = 2
            """, url);
        assertThat(v2).containsEntry("extracted_text", "version two content").containsEntry("summary", null);
    }

    @Test
    void quarantineAlsoStripsArchivedVersions() {
        String url = "http://quarantine-history-" + System.nanoTime() + ".onion";
        repository.upsertWithVersioning(doc(url, "version one content"));
        repository.upsertWithVersioning(doc(url, "version two content"));
        repository.upsertWithVersioning(doc(url, "version three content"));

        repository.quarantine(doc(url, "version four, blocked"), "url-denylist");

        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM page_versions
            WHERE page_id = (SELECT id FROM pages WHERE url = ?) AND extracted_text IS NOT NULL
            """, Integer.class, url)).isZero();
        assertThat(archivedCountFor(url)).isEqualTo(2);
    }
}
