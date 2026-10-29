package com.onionmind.acceptance;

import com.onionmind.HttpJson;
import com.onionmind.ai.FakeHttpServer;
import com.onionmind.ai.Report;
import io.cucumber.java.pt.Então;
import io.cucumber.java.pt.Quando;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The Agent Playground (Fase 5): {@code /api/agents} against the real pipeline, with the model faked. */
public class Fase5AgentSteps {

    @Autowired
    private CommonSteps common;
    @Autowired
    private FakeAiProviders fakes;

    private final Map<String, Long> ids = new HashMap<>();

    @Quando("eu listo os agentes")
    public void listAgents() {
        common.response = HttpJson.get(common.api("/api/agents"));
    }

    @Então("a lista de agentes contém {string}")
    public void assertListed(String name) {
        assertThat(common.response.status()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        common.response.json().forEach(a -> names.add(a.path("name").asString()));
        assertThat(names).contains(name);
    }

    @Quando("eu crio o agente {string} sobre {string} com as ferramentas {string} e teto de {int} tokens")
    public void createAgent(String name, String topic, String tools, int maxTokens) {
        common.response = HttpJson.post(common.api("/api/agents"), Map.of(
            "name", name, "instructions", "Summarize what the indexed content says about the topic.",
            "topic", topic, "categories", List.of(), "windowDays", 7, "tools", List.of(tools.split(",")),
            "costProfile", "batch", "maxTokensPerRun", maxTokens, "daily", false));
        if (common.response.status() == 201) {
            ids.put(name, common.response.json().path("id").asLong());
        }
    }

    @Quando("eu executo o agente {string}")
    public void run(String name) {
        common.response = HttpJson.post(common.api("/api/agents/" + ids.get(name) + "/runs"), Map.of());
    }

    @Quando("eu executo o agente {string} {int} vezes")
    public void run(String name, int times) {
        for (int i = 0; i < times; i++) {
            run(name);
            assertThat(common.response.status()).isEqualTo(200);
        }
    }

    @Quando("eu removo o agente {string}")
    public void delete(String name) {
        common.response = HttpJson.delete(common.api("/api/agents/" + ids.get(name)));
    }

    @Então("a execução termina {string} com um relatório em inglês")
    public void assertReported(String status) {
        JsonNode run = run();
        assertThat(run.path("status").asString()).isEqualTo(status);
        assertThat(run.path("report").asString()).contains(" the ").isNotEqualTo(Report.NO_MATERIAL);
        assertThat(run.path("tokensUsed").asInt()).isPositive();
    }

    @Então("a execução termina {string} com o motivo {string}")
    public void assertFailed(String status, String reason) {
        JsonNode run = run();
        assertThat(run.path("status").asString()).isEqualTo(status);
        assertThat(run.path("failureReason").asString()).isEqualTo(reason);
    }

    @Então("as fontes da execução vêm das ferramentas {string}")
    public void assertSourceTools(String tools) {
        Set<String> seen = new LinkedHashSet<>();
        run().path("sources").forEach(s -> seen.add(s.path("tool").asString()));
        assertThat(seen).containsExactly(tools.split(","));
    }

    @Então("o histórico do agente {string} tem {int} execução/execuções")
    public void assertHistory(String name, int count) {
        HttpJson runs = HttpJson.get(common.api("/api/agents/" + ids.get(name) + "/runs"));
        assertThat(runs.status()).isEqualTo(200);
        assertThat(runs.json().size()).isEqualTo(count);
    }

    @Então("o modelo não recebeu nenhum pedido de relatório")
    public void assertNoReportRequested() {
        assertThat(reportRequests()).isEmpty();
    }

    @Então("o modelo não recebeu nenhuma mudança como fonte")
    public void assertNoChangeSent() {
        assertThat(reportRequests()).isNotEmpty().noneMatch(body -> body.contains("Change on "));
    }

    @Então("o erro do campo {string} menciona {string}")
    public void assertFieldError(String field, String text) {
        assertThat(common.response.json().path("errors").path(field).asString()).contains(text);
    }

    @Então("consultar o agente {string} responde {int}")
    public void assertAgentStatus(String name, int status) {
        assertThat(HttpJson.get(common.api("/api/agents/" + ids.get(name))).status()).isEqualTo(status);
    }

    @Então("consultar as execuções do agente {string} responde {int}")
    public void assertRunsStatus(String name, int status) {
        assertThat(HttpJson.get(common.api("/api/agents/" + ids.get(name) + "/runs")).status()).isEqualTo(status);
    }

    private JsonNode run() {
        assertThat(common.response.status()).as(common.response.body()).isEqualTo(200);
        return common.response.json();
    }

    private List<String> reportRequests() {
        List<String> bodies = new ArrayList<>();
        for (FakeHttpServer server : List.of(fakes.gemini, fakes.deepseek)) {
            server.requestBodies.stream().filter(b -> b.contains("Write a research report")).forEach(bodies::add);
        }
        return bodies;
    }
}
