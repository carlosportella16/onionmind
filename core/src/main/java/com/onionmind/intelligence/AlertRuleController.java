package com.onionmind.intelligence;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Basic CRUD, no UI (design.md Non-Goals — an admin UI is Fase 5 Agent Playground territory, not this phase). */
@RestController
@RequestMapping("/api/alert-rules")
public class AlertRuleController {

    private final AlertRuleRepository repository;

    AlertRuleController(AlertRuleRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<AlertRule> list() {
        return repository.findActive();
    }

    @PostMapping
    public ResponseEntity<Long> create(@RequestBody CreateAlertRuleRequest request) {
        Long id = repository.create(request.criteriaType(), request.criteriaValue());
        return ResponseEntity.ok(id);
    }

    record CreateAlertRuleRequest(AlertCriteriaType criteriaType, String criteriaValue) {
    }
}
