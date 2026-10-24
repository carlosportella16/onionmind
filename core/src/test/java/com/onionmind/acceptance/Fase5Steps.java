package com.onionmind.acceptance;

import com.onionmind.HttpJson;
import com.onionmind.ai.Answer;
import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.Então;
import io.cucumber.java.pt.Quando;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The chat (Fase 5): {@code POST /api/chat} against the real pipeline, with the model faked. */
public class Fase5Steps {

    @Autowired
    private CommonSteps common;
    @Autowired
    private FakeAiProviders fakes;

    private Duration elapsed;

    @Dado("que o modelo de IA demora {int} segundos para responder ao chat")
    public void slowAnswers(int seconds) {
        fakes.answerDelayMillis = seconds * 1000L;
    }

    @Quando("eu pergunto ao chat {string}")
    public void ask(String question) {
        long start = System.nanoTime();
        common.response = HttpJson.post(common.api("/api/chat"), Map.of("question", question));
        elapsed = Duration.ofNanos(System.nanoTime() - start);
    }

    @Então("o chat responde em inglês, sem degradar")
    public void assertAnsweredInEnglish() {
        JsonNode chat = chat();
        assertThat(chat.path("degraded").asBoolean()).isFalse();
        assertThat(chat.path("answer").asString()).contains(" the ").isNotEqualTo(Answer.NO_ANSWER);
    }

    @Então("o chat diz que as páginas indexadas não contêm a resposta")
    public void assertNoAnswer() {
        assertThat(chat().path("answer").asString()).isEqualTo(Answer.NO_ANSWER);
    }

    @Então("o chat responde degradado, sem resposta")
    public void assertDegraded() {
        JsonNode chat = chat();
        assertThat(chat.path("degraded").asBoolean()).isTrue();
        assertThat(chat.path("answer").asString()).isEmpty();
        assertThat(chat.path("sources").size()).isZero();
    }

    @Então("a resposta do chat saiu em menos de {int} segundos")
    public void assertFast(int seconds) {
        assertThat(elapsed).isLessThan(Duration.ofSeconds(seconds));
    }

    @Então("as fontes do chat contêm a página {string}")
    public void assertSourcesContain(String url) {
        assertThat(sourceUrls()).contains(url);
    }

    @Então("as fontes do chat não contêm a página {string}")
    public void assertSourcesDoNotContain(String url) {
        assertThat(sourceUrls()).doesNotContain(url);
    }

    @Então("as fontes do chat vêm vazias")
    public void assertNoSources() {
        assertThat(sourceUrls()).isEmpty();
    }

    @Então("o modelo recebeu a pergunta {string} para reescrever em inglês")
    public void assertRewriteRequested(String question) {
        assertThat(fakes.gemini.requestBodies).anySatisfy(body -> assertThat(body)
            .contains("Rewrite the question as a short English search query", question));
    }

    /** In the raw request JSON the tag's quotes are escaped. */
    @Então("o modelo recebeu o trecho {string} delimitado como fonte")
    public void assertSentAsSource(String text) {
        assertThat(fakes.gemini.requestBodies).anySatisfy(body -> assertThat(body)
            .contains("Answer the question using only the sources", "<source id=\\\"S1\\\">", text));
    }

    private JsonNode chat() {
        assertThat(common.response.status()).as(common.response.body()).isEqualTo(200);
        return common.response.json();
    }

    private List<String> sourceUrls() {
        List<String> urls = new ArrayList<>();
        chat().path("sources").forEach(s -> urls.add(s.path("url").asString()));
        return urls;
    }
}
