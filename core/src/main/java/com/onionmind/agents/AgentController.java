package com.onionmind.agents;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code /api/agents}: CRUD (agents/agent-config) and runs (agents/agent-runs). A rejected
 * configuration is {@code {"errors": {field: message}}} — 400 when invalid, 409 when the name
 * is taken — so the playground can show each message next to its field.
 */
@RestController
@RequestMapping("/api/agents")
class AgentController {

    private final AgentRepository repo;
    private final AgentRunner runner;
    private final int maxInstructionsChars;

    AgentController(AgentRepository repo, AgentRunner runner,
                    @Value("${agents.max-instructions-chars:4000}") int maxInstructionsChars) {
        this.repo = repo;
        this.runner = runner;
        this.maxInstructionsChars = maxInstructionsChars;
    }

    @GetMapping
    List<Agent> list() {
        return repo.findAll();
    }

    @GetMapping("/{id}")
    ResponseEntity<Agent> get(@PathVariable long id) {
        return ResponseEntity.of(repo.find(id));
    }

    @PostMapping
    ResponseEntity<?> create(@RequestBody Agent body) {
        return save(body, agent -> ResponseEntity.status(HttpStatus.CREATED).body(repo.insert(agent)));
    }

    @PutMapping("/{id}")
    ResponseEntity<?> update(@PathVariable long id, @RequestBody Agent body) {
        return save(body, agent -> ResponseEntity.of(repo.update(id, agent)));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable long id) {
        return repo.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/runs")
    ResponseEntity<AgentRun> run(@PathVariable long id) {
        return ResponseEntity.of(repo.find(id).map(runner::run));
    }

    @GetMapping("/{id}/runs")
    ResponseEntity<List<AgentRun>> runs(@PathVariable long id) {
        return ResponseEntity.of(repo.find(id).map(a -> repo.runs(id)));
    }

    @GetMapping("/{id}/runs/{runId}")
    ResponseEntity<AgentRun> run(@PathVariable long id, @PathVariable long runId) {
        return ResponseEntity.of(repo.run(id, runId));
    }

    private ResponseEntity<?> save(Agent body, Function<Agent, ResponseEntity<?>> store) {
        Agent agent = body.normalized();
        Map<String, String> errors = agent.errors(maxInstructionsChars);
        if (!errors.isEmpty()) {
            return errors(HttpStatus.BAD_REQUEST, errors);
        }
        try {
            return store.apply(agent);
        } catch (DuplicateKeyException e) {
            return errors(HttpStatus.CONFLICT, Map.of("name", "an agent named \"" + agent.name() + "\" already exists"));
        }
    }

    private static ResponseEntity<?> errors(HttpStatus status, Map<String, String> errors) {
        return ResponseEntity.status(status).body(Map.of("errors", errors));
    }
}
