package com.onionmind.content;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmbeddingBackfillJobTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private EmbeddingProcessor embeddingProcessor;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("""
            CREATE TABLE pages (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                url VARCHAR(500) NOT NULL,
                source_type VARCHAR(50) NOT NULL,
                extracted_text CLOB,
                embedding_status VARCHAR(20) NOT NULL DEFAULT 'pending',
                embedding_attempted_at TIMESTAMP,
                embedding_error_message CLOB
            )
            """);
        embeddingProcessor = mock(EmbeddingProcessor.class);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    private void insertPage(String url, String status) {
        jdbc.update("INSERT INTO pages (url, source_type, extracted_text, embedding_status) VALUES (?, 'tor', 'some text', ?)",
            url, status);
    }

    private String statusOf(String url) {
        return jdbc.queryForObject("SELECT embedding_status FROM pages WHERE url = ?", String.class, url);
    }

    @Test
    void picksUpPendingPagesAndMarksThemEmbedded() {
        insertPage("http://backfill-me.onion", "pending");
        when(embeddingProcessor.process(any())).thenAnswer(inv -> {
            Document doc = inv.getArgument(0);
            return ProcessingResult.success(doc);
        });

        var job = new EmbeddingBackfillJob(jdbc, embeddingProcessor, 10);
        job.run();

        assertThat(statusOf("http://backfill-me.onion")).isEqualTo(EmbeddingOutcome.EMBEDDED);
        verify(embeddingProcessor).process(any());
    }

    @Test
    void retriesPagesThatPreviouslyFailedTransiently() {
        insertPage("http://retry-me.onion", "failed_transient");
        when(embeddingProcessor.process(any())).thenAnswer(inv -> {
            Document doc = inv.getArgument(0);
            return ProcessingResult.success(doc);
        });

        var job = new EmbeddingBackfillJob(jdbc, embeddingProcessor, 10);
        job.run();

        assertThat(statusOf("http://retry-me.onion")).isEqualTo(EmbeddingOutcome.EMBEDDED);
    }

    @Test
    void doesNotTouchPagesAlreadyEmbedded() {
        insertPage("http://already-done.onion", "embedded");

        var job = new EmbeddingBackfillJob(jdbc, embeddingProcessor, 10);
        job.run();

        verifyNoInteractions(embeddingProcessor);
        assertThat(statusOf("http://already-done.onion")).isEqualTo("embedded");
    }

    @Test
    void failedAttemptRecordsErrorMessage() {
        insertPage("http://still-broken.onion", "pending");
        when(embeddingProcessor.process(any())).thenAnswer(inv -> {
            Document doc = inv.getArgument(0);
            return ProcessingResult.failed(doc, "ollama unreachable");
        });

        var job = new EmbeddingBackfillJob(jdbc, embeddingProcessor, 10);
        job.run();

        assertThat(statusOf("http://still-broken.onion")).isEqualTo(EmbeddingOutcome.FAILED_TRANSIENT);
        String error = jdbc.queryForObject(
            "SELECT embedding_error_message FROM pages WHERE url = ?", String.class, "http://still-broken.onion");
        assertThat(error).isEqualTo("ollama unreachable");
    }

    @Test
    void batchSizeLimitsHowManyPagesAreProcessedPerRun() {
        insertPage("http://one.onion", "pending");
        insertPage("http://two.onion", "pending");
        when(embeddingProcessor.process(any())).thenAnswer(inv -> {
            Document doc = inv.getArgument(0);
            return ProcessingResult.success(doc);
        });

        var job = new EmbeddingBackfillJob(jdbc, embeddingProcessor, 1);
        job.run();

        verify(embeddingProcessor, times(1)).process(any());
    }

    @Test
    void noPendingPagesDoesNothing() {
        var job = new EmbeddingBackfillJob(jdbc, embeddingProcessor, 10);

        job.run();

        verifyNoInteractions(embeddingProcessor);
    }
}
