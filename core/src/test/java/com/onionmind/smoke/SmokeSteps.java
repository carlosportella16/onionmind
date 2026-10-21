package com.onionmind.smoke;

import com.onionmind.HttpJson;
import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.Então;
import io.cucumber.java.pt.Quando;

import static org.assertj.core.api.Assertions.assertThat;

/** Plain HTTP glue: deliberately no Spring, so cucumber-spring never boots a context here. */
public class SmokeSteps {

    static final String BASE_URL = baseUrl();

    private HttpJson response;
    private HttpJson statsBefore;

    @Quando("eu consulto {string}")
    public void get(String path) {
        response = HttpJson.get(BASE_URL + path);
    }

    @Então("a resposta tem status {int}")
    public void assertStatus(int status) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
    }

    @Então("o campo {string} é {string}")
    public void assertField(String field, String value) {
        assertThat(response.json().path(field).asString()).isEqualTo(value);
    }

    @Então("a resposta tem a lista {string}")
    public void assertList(String field) {
        assertThat(response.json().path(field).isArray()).as(response.body()).isTrue();
    }

    @Então("a resposta tem a lista {string} e os campos {string}, {string} e {string}")
    public void assertListAndFields(String list, String a, String b, String c) {
        assertList(list);
        for (String field : new String[]{a, b, c}) {
            assertThat(response.json().path(field).isNumber()).as(field + " in " + response.body()).isTrue();
        }
    }

    @Então("os campos {string} e {string} são numéricos")
    public void assertNumeric(String a, String b) {
        assertThat(response.json().path(a).isNumber()).as(response.body()).isTrue();
        assertThat(response.json().path(b).isNumber()).as(response.body()).isTrue();
    }

    @Então("o corpo contém as séries {string} e {string}")
    public void assertSeries(String a, String b) {
        assertThat(response.body()).contains(a).contains(b);
    }

    @Dado("que eu anotei as estatísticas")
    public void snapshotStats() {
        statsBefore = HttpJson.get(BASE_URL + "/api/stats");
        assertThat(statsBefore.status()).isEqualTo(200);
    }

    @Então("as estatísticas não mudaram")
    public void assertStatsUnchanged() {
        HttpJson after = HttpJson.get(BASE_URL + "/api/stats");
        for (String field : new String[]{"total_pages", "total_versions"}) {
            assertThat(after.json().path(field).asLong()).as(field)
                .isEqualTo(statsBefore.json().path(field).asLong());
        }
    }

    private static String baseUrl() {
        String url = System.getenv("SMOKE_BASE_URL");
        url = url == null || url.isBlank() ? "http://localhost:8081" : url;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
