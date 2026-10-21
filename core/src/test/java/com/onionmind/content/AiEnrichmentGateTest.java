package com.onionmind.content;

import com.onionmind.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AiEnrichmentGateTest {

    @Autowired
    private JdbcTemplate jdbc;

    private AiEnrichmentGate gate;

    @BeforeEach
    void setUp() {
        gate = new AiEnrichmentGate(jdbc);
    }

    private Document doc(String url, String text) {
        return new Document(url, "tor", "<html>x</html>", text, DocumentType.HTML);
    }

    private void insert(String url, String text, String status) {
        String hash = doc(url, text).resolvedContentHash();
        jdbc.update("""
            INSERT INTO pages (url, source_type, extracted_text, content_hash, version, ai_status)
            VALUES (?, 'tor', ?, ?, 1, ?)
            """, url, text, hash, status);
    }

    @Test
    void trueWhenSameContentWasAlreadyProcessed() {
        String url = "http://enriched-" + System.nanoTime() + ".onion";
        insert(url, "stable content", "processed");

        assertThat(gate.alreadyEnriched(doc(url, "stable content"))).isTrue();
    }

    @Test
    void falseWhenContentChanged() {
        String url = "http://changed-" + System.nanoTime() + ".onion";
        insert(url, "old content", "processed");

        assertThat(gate.alreadyEnriched(doc(url, "new different content"))).isFalse();
    }

    @Test
    void falseWhenNotYetProcessed() {
        String url = "http://pending-" + System.nanoTime() + ".onion";
        insert(url, "some content", "pending");

        assertThat(gate.alreadyEnriched(doc(url, "some content"))).isFalse();
    }

    @Test
    void falseForAnUnknownPage() {
        assertThat(gate.alreadyEnriched(doc("http://never-seen.onion", "whatever"))).isFalse();
    }
}
