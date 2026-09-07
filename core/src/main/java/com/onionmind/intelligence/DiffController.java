package com.onionmind.intelligence;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Own read endpoint, not centralized in {@code search} — design.md D8 (revised to avoid a module cycle). */
@RestController
@RequestMapping("/api/pages")
public class DiffController {

    private final PageDiffRepository repository;

    DiffController(PageDiffRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/diff")
    public ResponseEntity<PageDiffView> latestDiff(@RequestParam String url) {
        if (url.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return repository.findLatestByUrl(url)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
