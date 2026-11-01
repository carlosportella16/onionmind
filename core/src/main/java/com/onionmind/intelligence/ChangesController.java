package com.onionmind.intelligence;

import com.onionmind.content.Categories;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * "What changed in this category since yesterday" — served from {@code page_changes}, never
 * a live LLM call (sdd sec. 9). Trivial changes are not changes.
 */
@RestController
@RequestMapping("/api")
class ChangesController {

    static final Duration DEFAULT_WINDOW = Duration.ofHours(24);
    static final Duration MAX_WINDOW = Duration.ofDays(30);
    static final int MAX_LIMIT = 100;

    private final ChangeQuery changes;
    private final Clock clock;

    @Autowired
    ChangesController(ChangeQuery changes) {
        this(changes, Clock.systemUTC());
    }

    ChangesController(ChangeQuery changes, Clock clock) {
        this.changes = changes;
        this.clock = clock;
    }

    @GetMapping("/changes")
    ResponseEntity<ChangesResponse> changes(@RequestParam(required = false) String since,
                                            @RequestParam(required = false) String category,
                                            @RequestParam(defaultValue = "20") int limit) {
        Instant now = clock.instant();
        Instant from;
        try {
            from = since == null ? now.minus(DEFAULT_WINDOW) : Instant.parse(since);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().build();
        }
        if (from.isAfter(now) || from.isBefore(now.minus(MAX_WINDOW)) || limit < 1
            || (category != null && !Categories.TAXONOMY.contains(category))) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(changes.find(from, category == null ? List.of() : List.of(category), false,
            Math.min(limit, MAX_LIMIT)));
    }
}
