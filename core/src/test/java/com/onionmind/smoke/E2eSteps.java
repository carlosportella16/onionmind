package com.onionmind.smoke;

import com.onionmind.HttpJson;
import io.cucumber.java.pt.Então;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Waits for the crawl to show up through the core's API (features/smoke/e2e.feature, run by
 * scripts/e2e-smoke.sh). Read-only like the rest of the smoke glue (the chat's POST changes
 * nothing); the e2e stack has no "crawl finished" signal, so every positive check polls until
 * its deadline.
 */
public class E2eSteps {

    @Então("em até {int} segundos a busca por {string} contém a página {string}")
    public void eventuallySearchContains(int seconds, String query, String url) {
        await().atMost(Duration.ofSeconds(seconds)).pollInterval(Duration.ofSeconds(1))
            .untilAsserted(() -> assertThat(urls(search("/api/search", query))).contains(url));
    }

    @Então("em até {int} segundos a busca semântica por {string} contém a página {string}")
    public void eventuallySemanticContains(int seconds, String query, String url) {
        await().atMost(Duration.ofSeconds(seconds)).pollInterval(Duration.ofSeconds(1))
            .untilAsserted(() -> assertThat(urls(search("/api/search/semantic", query))).contains(url));
    }

    @Então("em até {int} segundos o resultado de {string} na busca por {string} traz resumo e categoria")
    public void eventuallyEnriched(int seconds, String url, String query) {
        await().atMost(Duration.ofSeconds(seconds)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            JsonNode result = find(search("/api/search", query), url)
                .orElseThrow(() -> new AssertionError(url + " not in results for " + query));
            assertThat(result.path("summary").asString("")).isNotBlank();
            assertThat(result.path("category").asString("")).isNotBlank();
        });
    }

    @Então("a busca por {string} não contém a página {string}")
    public void searchDoesNotContain(String query, String url) {
        assertThat(urls(search("/api/search", query))).doesNotContain(url);
    }

    @Então("em até {int} segundos o total de páginas é pelo menos {int}")
    public void eventuallyTotalPagesAtLeast(int seconds, int min) {
        await().atMost(Duration.ofSeconds(seconds)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
            assertThat(HttpJson.get(SmokeSteps.BASE_URL + "/api/stats").json().path("total_pages").asLong())
                .isGreaterThanOrEqualTo(min));
    }

    @Então("em até {int} segundos a entidade {string} {string} lista a página {string}")
    public void eventuallyEntityListsPage(int seconds, String type, String key, String url) {
        await().atMost(Duration.ofSeconds(seconds)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            HttpJson response = HttpJson.get(SmokeSteps.BASE_URL + "/api/entities/" + type + "/" + HttpJson.encode(key));
            assertThat(response.status()).as(response.body()).isEqualTo(200);
            List<String> pages = new ArrayList<>();
            response.json().path("pages").forEach(p -> pages.add(p.path("url").asString()));
            assertThat(pages).contains(url);
        });
    }

    /** POST, but /api/chat only reads: the glue still changes nothing on the instance. */
    @Então("em até {int} segundos o chat responde a {string} citando a página {string}")
    public void eventuallyChatCites(int seconds, String question, String url) {
        await().atMost(Duration.ofSeconds(seconds)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            HttpJson response = HttpJson.post(SmokeSteps.BASE_URL + "/api/chat", Map.of("question", question));
            assertThat(response.status()).as(response.body()).isEqualTo(200);
            assertThat(response.json().path("degraded").asBoolean()).as(response.body()).isFalse();
            assertThat(response.json().path("answer").asString()).isNotBlank();
            List<String> sources = new ArrayList<>();
            response.json().path("sources").forEach(s -> sources.add(s.path("url").asString()));
            assertThat(sources).contains(url);
        });
    }

    private static List<JsonNode> search(String path, String query) {
        HttpJson response = HttpJson.get(SmokeSteps.BASE_URL + path + "?q=" + HttpJson.encode(query));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        List<JsonNode> out = new ArrayList<>();
        response.json().path("results").forEach(out::add);
        return out;
    }

    private static List<String> urls(List<JsonNode> results) {
        return results.stream().map(r -> r.path("url").asString()).toList();
    }

    private static Optional<JsonNode> find(List<JsonNode> results, String url) {
        return results.stream().filter(r -> url.equals(r.path("url").asString())).findFirst();
    }
}
