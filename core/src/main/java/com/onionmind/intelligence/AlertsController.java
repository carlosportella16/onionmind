package com.onionmind.intelligence;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Own read endpoint, not centralized in {@code search} — design.md D8 (revised to avoid a module cycle). */
@RestController
@RequestMapping("/api/alerts")
public class AlertsController {

    private final AlertRepository repository;

    AlertsController(AlertRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<AlertRepository.AlertView> list() {
        return repository.findAll();
    }
}
