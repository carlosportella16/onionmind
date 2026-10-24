package com.onionmind.acceptance;

import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.Então;
import io.cucumber.java.pt.Quando;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class Fase3Steps {

    /** Mirrors ClassifierProcessor.TAXONOMY — the contract, restated here on purpose. */
    private static final Set<String> TAXONOMY =
        Set.of("marketplace", "forum", "blog", "service", "institutional", "other");

    @Autowired
    private CommonSteps common;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private FakeAiProviders fakes;

    @Dado("que o modelo de IA responde a categoria {string}")
    public void modelAnswersCategory(String category) {
        fakes.categoryAnswer = category;
    }

    /** Fills the per-minute counter the way a burst of real traffic (or a 429) would — design D7. */
    @Dado("que o provedor {string} está sem cota no minuto corrente")
    public void exhaustMinuteQuota(String provider) {
        long minute = Instant.now().getEpochSecond() / 60;
        for (long m = minute; m <= minute + 1; m++) { // survive a minute boundary mid-scenario
            redis.opsForValue().set("quota:%s:minute:%d".formatted(provider, m), "1000000", Duration.ofSeconds(150));
        }
    }

    /** The fake answers HTTP 402, like DeepSeek with no prepaid balance left. */
    @Dado("que o provedor {string} está sem saldo")
    public void outOfBalance(String provider) {
        server(provider).status = 402;
        server(provider).responder = null;
    }

    /** What an operator does after topping up: the documented DEL of the park key. */
    @Quando("o saldo do provedor {string} é liberado")
    public void releaseBalance(String provider) {
        fakes.restore(server(provider));
        redis.delete("quota:%s:parked".formatted(provider));
    }

    @Então("a página {string} continua aguardando IA sem tentativas gastas")
    public void assertWaitingWithoutAttempts(String url) {
        Map<String, Object> row = jdbc.queryForMap("SELECT ai_status, ai_attempts FROM pages WHERE url = ?", url);
        assertThat(row).containsEntry("ai_status", "pending").containsEntry("ai_attempts", 0);
    }

    @Então("a página {string} está com status de IA {string}")
    public void assertAiStatus(String url, String status) {
        assertThat(page(url).get("ai_status")).isEqualTo(status);
    }

    @Então("a página {string} tem resumo, categoria e idioma preenchidos")
    public void assertEnriched(String url) {
        Map<String, Object> row = page(url);
        assertThat(row.get("summary")).asString().isNotBlank();
        assertThat(row.get("category")).asString().isNotBlank();
        assertThat(row.get("language")).asString().isNotBlank();
    }

    @Então("o idioma da página {string} é {string}")
    public void assertLanguage(String url, String language) {
        assertThat(page(url).get("language")).isEqualTo(language);
    }

    /** The summary prompt is English and asks for English; the page text goes along untouched. */
    @Então("o modelo recebeu o pedido de resumo em inglês com o texto original da página {string}")
    public void assertEnglishSummaryPrompt(String url) {
        String original = (String) page(url).get("extracted_text");
        assertThat(generationBodies()).anySatisfy(body ->
            assertThat(body).contains("You summarize web pages", "Respond in English", original));
    }

    @Então("nenhuma tradução foi pedida ao modelo")
    public void assertNoTranslationRequested() {
        assertThat(generationBodies()).noneMatch(body -> body.contains("Translate the text"));
    }

    @Então("o texto original da página {string} continua contendo {string}")
    public void assertOriginalKept(String url, String fragment) {
        assertThat(page(url).get("extracted_text")).asString().contains(fragment);
    }

    @Então("a categoria da página {string} pertence à taxonomia")
    public void assertCategoryInTaxonomy(String url) {
        assertThat(TAXONOMY).contains((String) page(url).get("category"));
    }

    @Então("a categoria da página {string} não é {string}")
    public void assertCategoryIsNot(String url, String category) {
        assertThat(page(url).get("category")).isNotEqualTo(category);
    }

    @Então("a página {string} está em quarentena sem texto nem HTML")
    public void assertQuarantined(String url) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quarantined_pages WHERE url = ?", Integer.class, url))
            .isEqualTo(1);
        Map<String, Object> row = page(url);
        assertThat(row.get("extracted_text")).isNull();
        assertThat(row.get("raw_html")).isNull();
    }

    @Então("o provedor {string} não recebeu chamadas de geração")
    public void assertNoGenerationCalls(String provider) {
        assertThat(FakeAiProviders.generationCalls(server(provider))).isZero();
    }

    @Então("o provedor {string} recebeu chamadas de geração")
    public void assertGenerationCalls(String provider) {
        assertThat(FakeAiProviders.generationCalls(server(provider))).isPositive();
    }

    @Então("o resultado da página {string} traz resumo e categoria")
    public void assertResultEnriched(String url) {
        JsonNode result = common.resultFor(url);
        assertThat(result.path("summary").asString("")).isNotBlank();
        assertThat(result.path("category").asString("")).isNotBlank();
    }

    @Então("o resultado da página {string} vem sem resumo nem categoria")
    public void assertResultNotEnriched(String url) {
        JsonNode result = common.resultFor(url);
        assertThat(result.path("summary").isNull() || result.path("summary").isMissingNode()).isTrue();
        assertThat(result.path("category").isNull() || result.path("category").isMissingNode()).isTrue();
    }

    private com.onionmind.ai.FakeHttpServer server(String provider) {
        return switch (provider) {
            case "ollama" -> fakes.ollama;
            case "deepseek" -> fakes.deepseek;
            case "gemini" -> fakes.gemini;
            default -> throw new IllegalArgumentException("unknown provider " + provider);
        };
    }

    private java.util.List<String> generationBodies() {
        return java.util.stream.Stream.of(fakes.ollama, fakes.deepseek, fakes.gemini)
            .flatMap(s -> s.requestBodies.stream()).toList();
    }

    private Map<String, Object> page(String url) {
        return jdbc.queryForMap("""
            SELECT ai_status, extracted_text, raw_html,
                   summary->>'text' AS summary, category->>'category' AS category,
                   language->>'code' AS language
            FROM pages WHERE url = ?
            """, url);
    }
}
