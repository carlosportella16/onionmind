package com.onionmind.acceptance;

import com.onionmind.HttpJson;
import com.onionmind.intelligence.ChangeSummaryJob;
import io.cucumber.java.pt.Então;
import io.cucumber.java.pt.Quando;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class Fase4Steps {

    @Autowired
    private CommonSteps common;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ChangeSummaryJob changeSummaries;

    @Quando("o ciclo de resumo de mudanças roda")
    public void runChangeSummaries() {
        changeSummaries.run(); // same job the scheduler runs (design D9), triggered
    }

    @Quando("os eventos foram entregues")
    public void eventsDelivered() {
        common.awaitEventsDelivered();
    }

    @Quando("eu pergunto o que mudou na categoria {string} desde ontem")
    public void askWhatChanged(String category) {
        String since = Instant.now().minus(Duration.ofDays(1)).toString();
        common.response = HttpJson.get(common.api("/api/changes?category=" + category + "&since=" + since));
    }

    @Então("as mudanças listadas são somente das páginas {string}")
    public void assertChangedPages(String urls) {
        assertThat(changes()).extracting(c -> c.path("url").asString()).containsExactlyInAnyOrder(split(urls));
        assertThat(common.response.json().path("total").asLong()).isEqualTo(split(urls).length);
    }

    @Então("nenhuma mudança é listada")
    public void assertNoChanges() {
        assertThat(changes()).isEmpty();
    }

    @Então("a mudança da página {string} traz um resumo")
    public void assertChangeSummarized(String url) {
        JsonNode change = change(url);
        assertThat(change.path("status").asString()).isEqualTo("summarized");
        assertThat(change.path("summary").path("text").asString("")).isNotBlank();
    }

    @Então("a mudança da página {string} lista o trecho adicionado {string}")
    public void assertAddedText(String url, String sentence) {
        String added = jdbc.queryForObject(
            "SELECT added_text FROM page_changes WHERE url = ? ORDER BY to_version DESC LIMIT 1", String.class, url);
        assertThat(added).isEqualTo(sentence);
    }

    @Então("a mudança da página {string} adiciona a entidade {string}")
    public void assertEntityAdded(String url, String type) {
        List<String> types = new ArrayList<>();
        change(url).path("entitiesAdded").forEach(e -> types.add(e.path("type").asString()));
        assertThat(types).contains(type);
    }

    @Então("a entidade {string} {string} é citada pelas páginas {string}")
    public void assertEntityMentionedBy(String type, String key, String urls) {
        HttpJson r = entity(type, key);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        List<String> pages = new ArrayList<>();
        r.json().path("pages").forEach(p -> pages.add(p.path("url").asString()));
        assertThat(pages).containsExactlyInAnyOrder(split(urls));
    }

    @Então("a entidade {string} {string} aparece junto de {string} {string}")
    public void assertRelated(String type, String key, String relatedType, String relatedKey) {
        List<String> related = new ArrayList<>();
        entity(type, key).json().path("related")
            .forEach(e -> related.add(e.path("type").asString() + ":" + e.path("key").asString()));
        assertThat(related).contains(relatedType + ":" + relatedKey);
    }

    @Então("consultar a entidade {string} {string} responde {int}")
    public void assertEntityStatus(String type, String key, int status) {
        assertThat(entity(type, key).status()).isEqualTo(status);
    }

    @Então("nenhuma versão arquivada da página {string} guarda texto")
    public void assertNoArchivedText(String url) {
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM page_versions
            WHERE page_id = (SELECT id FROM pages WHERE url = ?) AND extracted_text IS NOT NULL
            """, Integer.class, url)).isZero();
    }

    private HttpJson entity(String type, String key) {
        return HttpJson.get(common.api("/api/entities/" + type + "/" + HttpJson.encode(key).replace("+", "%20")));
    }

    private List<JsonNode> changes() {
        assertThat(common.response.status()).as(common.response.body()).isEqualTo(200);
        List<JsonNode> out = new ArrayList<>();
        common.response.json().path("changes").forEach(out::add);
        return out;
    }

    private JsonNode change(String url) {
        return changes().stream().filter(c -> url.equals(c.path("url").asString())).findFirst()
            .orElseThrow(() -> new AssertionError("no change for " + url + " in " + common.response.body()));
    }

    private static String[] split(String urls) {
        return Arrays.stream(urls.split(",")).map(String::strip).toArray(String[]::new);
    }
}
