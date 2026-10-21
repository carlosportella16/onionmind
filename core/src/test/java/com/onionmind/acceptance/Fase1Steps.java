package com.onionmind.acceptance;

import com.onionmind.HttpJson;
import io.cucumber.java.pt.Então;
import io.cucumber.java.pt.Quando;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

public class Fase1Steps {

    @Autowired
    private CommonSteps common;
    @Autowired
    private JdbcTemplate jdbc;

    @Então("a página {string} está na versão {int}")
    public void assertVersion(String url, int version) {
        assertThat(jdbc.queryForObject("SELECT version FROM pages WHERE url = ?", Integer.class, url))
            .isEqualTo(version);
    }

    @Então("existe(m) {int} versão/versões arquivada(s) da página {string}")
    public void assertArchivedVersions(int count, String url) {
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM page_versions v JOIN pages p ON p.id = v.page_id WHERE p.url = ?
            """, Integer.class, url)).isEqualTo(count);
    }

    @Então("o texto persistido da página {string} não contém {string}")
    public void assertStoredTextExcludes(String url, String fragment) {
        String text = jdbc.queryForObject("SELECT extracted_text FROM pages WHERE url = ?", String.class, url);
        assertThat(text).isNotBlank().doesNotContain(fragment);
    }

    @Então("nenhum trecho dos resultados contém {string}")
    public void assertNoSnippetContains(String fragment) {
        assertThat(common.results()).allSatisfy(r -> assertThat(r.path("snippet").asString()).doesNotContain(fragment));
    }

    @Quando("eu consulto as estatísticas")
    public void stats() {
        common.response = HttpJson.get(common.api("/api/stats"));
    }

    @Então("o total de páginas é {int}")
    public void assertTotalPages(int total) {
        assertThat(common.response.status()).isEqualTo(200);
        assertThat(common.response.json().path("total_pages").asLong()).isEqualTo(total);
    }
}
