package com.onionmind.agents;

import com.onionmind.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The agents CRUD (agents/agent-config) against real Postgres; runs are AgentRunnerTest's. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AgentControllerTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AgentController controller;
    @Autowired
    private AgentRepository repo;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM agents WHERE name <> 'Research Agent'");
    }

    static Agent config(String name) {
        return new Agent(null, name, "Be brief.", "passports", List.of("forum"), 7,
            List.of("search", "changes"), "interactive", 4000, false, null, null);
    }

    @Test
    void validAgentIsSavedWithAnIdAndListed() {
        ResponseEntity<?> created = controller.create(config("  Passport watcher "));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Agent agent = (Agent) created.getBody();
        assertThat(agent.id()).isNotNull();
        assertThat(agent.name()).isEqualTo("Passport watcher");
        assertThat(agent.categories()).containsExactly("forum");
        assertThat(agent.tools()).containsExactly("search", "changes");
        assertThat(agent.createdAt()).isNotNull();
        assertThat(controller.list()).extracting(Agent::name).contains("Passport watcher", "Research Agent");
        assertThat(controller.get(agent.id()).getBody()).isEqualTo(agent);
    }

    @Test
    void theResearchAgentIsSeeded() {
        assertThat(controller.list()).filteredOn(a -> a.name().equals("Research Agent")).singleElement()
            .satisfies(a -> {
                assertThat(a.tools()).containsExactly("search", "changes", "entities");
                assertThat(a.costProfile()).isEqualTo("batch");
                assertThat(a.daily()).isFalse();
            });
    }

    @Test
    void unknownToolIs400NamingIt() {
        Agent bad = withTools(config("a"), List.of("search", "telnet"));

        assertThat(errors(controller.create(bad), HttpStatus.BAD_REQUEST))
            .containsExactly(Map.entry("tools", "unknown tool: telnet"));
    }

    @Test
    void categoryOutsideTheTaxonomyIs400() {
        Agent c = config("a");
        Agent bad = new Agent(null, c.name(), c.instructions(), c.topic(), List.of("weapons"), c.windowDays(), c.tools(),
            c.costProfile(), c.maxTokensPerRun(), c.daily(), null, null);

        assertThat(errors(controller.create(bad), HttpStatus.BAD_REQUEST)).containsKey("categories");
    }

    @Test
    void duplicateNameIs409OnCreateAndOnUpdate() {
        controller.create(config("Twin"));
        Agent other = (Agent) controller.create(config("Other")).getBody();

        assertThat(errors(controller.create(config("Twin")), HttpStatus.CONFLICT)).containsKey("name");
        assertThat(errors(controller.update(other.id(), config("Twin")), HttpStatus.CONFLICT)).containsKey("name");
    }

    @Test
    void sizeLimitsAndMissingFieldsAre400PerField() {
        Agent empty = new Agent(null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(errors(controller.create(empty), HttpStatus.BAD_REQUEST).keySet())
            .containsExactlyInAnyOrder("name", "topic", "windowDays", "tools", "costProfile", "maxTokensPerRun");

        Agent huge = new Agent(null, "x".repeat(101), "y".repeat(4001), "z".repeat(201), List.of(), 31,
            List.of("search"), "cheap", 100_001, false, null, null);
        assertThat(errors(controller.create(huge), HttpStatus.BAD_REQUEST).keySet())
            .containsExactlyInAnyOrder("name", "instructions", "topic", "windowDays", "costProfile", "maxTokensPerRun");
    }

    @Test
    void updateChangesTheConfigAndUnknownIdIs404() {
        Agent agent = (Agent) controller.create(config("Editable")).getBody();

        ResponseEntity<?> updated = controller.update(agent.id(), withTools(config("Edited"), List.of("entities")));

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Agent) updated.getBody()).tools()).containsExactly("entities");
        assertThat(controller.get(agent.id()).getBody().name()).isEqualTo("Edited");
        assertThat(controller.update(-1, config("Ghost")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.get(-1).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void removingAnAgentRemovesItsRuns() {
        Agent agent = (Agent) controller.create(config("Short-lived")).getBody();
        for (int i = 0; i < 3; i++) {
            repo.startRun(agent.id(), "manual");
        }
        assertThat(controller.runs(agent.id()).getBody()).hasSize(3);

        assertThat(controller.delete(agent.id()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(controller.get(agent.id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.runs(agent.id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE agent_id = ?", Integer.class, agent.id()))
            .isZero();
        assertThat(controller.delete(agent.id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    static Agent withTools(Agent a, List<String> tools) {
        return new Agent(a.id(), a.name(), a.instructions(), a.topic(), a.categories(), a.windowDays(), tools,
            a.costProfile(), a.maxTokensPerRun(), a.daily(), null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> errors(ResponseEntity<?> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        return ((Map<String, Map<String, String>>) response.getBody()).get("errors");
    }
}
