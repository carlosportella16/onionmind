package com.onionmind;

import com.onionmind.search.SearchController;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class Fase1EndToEndTest {

    @Autowired
    private KafkaContainer kafkaContainer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SearchController search;

    private KafkaProducer<String, String> producer;

    @BeforeEach
    void setUpProducer() {
        var props = Map.<String, Object>of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        producer = new KafkaProducer<>(props, new StringSerializer(), new StringSerializer());
    }

    @AfterEach
    void tearDownProducer() {
        producer.close();
    }

    private void publish(String url, String payload) throws Exception {
        producer.send(new ProducerRecord<>("raw-pages", url, payload)).get();
    }

    private String rawPageJson(String url, String htmlBody) {
        return """
            {"url":"%s","source_type":"tor",
             "html":"%s",
             "fetched_at":"2026-07-25T10:00:00Z"}
            """.formatted(url, htmlBody);
    }

    private boolean pageExists(String url) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM pages WHERE url = ?", Integer.class, url);
        return count != null && count > 0;
    }

    private int pageVersion(String url) {
        return jdbc.queryForObject("SELECT version FROM pages WHERE url = ?", Integer.class, url);
    }

    @Test
    void endToEnd_publishEvent_becomesSearchable() throws Exception {
        String url = "http://test-" + System.nanoTime() + ".onion/page";
        String event = rawPageJson(url,
            "<html><body><p>Bitcoin forum for anonymous trading</p></body></html>");
        publish(url, event);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(pageExists(url)).isTrue();
        });

        var response = search.search("bitcoin", 0, 20);
        assertThat(response.getBody().results())
            .extracting(com.onionmind.search.SearchResult::url)
            .contains(url);
    }

    @Test
    void versionIncrementsWhenContentChanges() throws Exception {
        String url = "http://v-" + System.nanoTime() + ".onion";

        publish(url, rawPageJson(url, "<p>version one content here</p>"));
        await().atMost(Duration.ofSeconds(10)).until(() -> pageExists(url));

        publish(url, rawPageJson(url, "<p>version two different content here</p>"));
        await().atMost(Duration.ofSeconds(10)).until(() -> pageVersion(url) == 2);

        var archived = jdbc.queryForObject(
            "SELECT count(*) FROM page_versions WHERE page_id = (SELECT id FROM pages WHERE url = ?)",
            Integer.class, url);
        assertThat(archived).isEqualTo(1);
    }
}
