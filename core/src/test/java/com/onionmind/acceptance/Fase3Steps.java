package com.onionmind.acceptance;

import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.Então;
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

    @Dado("que a página {string} foi publicada com um texto longo em português")
    public void publishLongText(String url) {
        // > 500 approx tokens (2000+ chars): the router sends this to the cloud ladder first.
        String sentence = "Este artigo em português explica com calma como a comunidade organiza os seus "
            + "encontros, quais regras de convivência existem e por que a privacidade importa para todos. ";
        common.publishText(url, sentence.repeat(14));
    }

    /** Fills the per-minute counter the way a burst of real traffic (or a 429) would — design D7. */
    @Dado("que o provedor {string} está sem cota no minuto corrente")
    public void exhaustMinuteQuota(String provider) {
        long minute = Instant.now().getEpochSecond() / 60;
        for (long m = minute; m <= minute + 1; m++) { // survive a minute boundary mid-scenario
            redis.opsForValue().set("quota:%s:minute:%d".formatted(provider, m), "1000000", Duration.ofSeconds(150));
        }
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

    @Então("a página {string} tem tradução preenchida")
    public void assertTranslated(String url) {
        assertThat(page(url).get("translation")).asString().isNotBlank();
    }

    @Então("a página {string} não tem tradução")
    public void assertNotTranslated(String url) {
        assertThat(page(url).get("translation")).isNull();
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
            case "groq" -> fakes.groq;
            case "gemini" -> fakes.gemini;
            default -> throw new IllegalArgumentException("unknown provider " + provider);
        };
    }

    private Map<String, Object> page(String url) {
        return jdbc.queryForMap("""
            SELECT ai_status, extracted_text, raw_html,
                   summary->>'text' AS summary, category->>'category' AS category,
                   language->>'code' AS language, translated_text->>'text' AS translation
            FROM pages WHERE url = ?
            """, url);
    }
}
