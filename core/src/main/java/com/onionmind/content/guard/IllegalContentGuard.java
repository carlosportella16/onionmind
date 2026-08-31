package com.onionmind.content.guard;

import com.onionmind.content.ContentProcessor;
import com.onionmind.content.Document;
import com.onionmind.content.DocumentType;
import com.onionmind.content.ProcessingResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Runs before any model touches the page (order 5, after the sanitizer) — the illegal-content
 * barrier the SDD requires with "no MVP shortcut" (sec. 8 / fase3-sdd sec. 7.5). Scope in this
 * phase: URL/host denylist + audited text patterns. Perceptual image hashing is a documented
 * gap — the pipeline persists no images. A match returns HALT; the pipeline does the rest
 * (audit row + stripping the page).
 */
@Component
public class IllegalContentGuard implements ContentProcessor {

    private static final Logger log = LoggerFactory.getLogger(IllegalContentGuard.class);

    private final Set<String> deniedHosts;
    private final List<Pattern> textPatterns;
    private final MeterRegistry registry;

    @Autowired
    public IllegalContentGuard(
            @Value("${illegal-content.url-denylist-path:classpath:security/onion-denylist.txt}") Resource denylist,
            @Value("${illegal-content.text-pattern-path:classpath:security/illegal-text-patterns.txt}") Resource patterns,
            MeterRegistry registry) {
        this.deniedHosts = loadHosts(denylist);
        this.textPatterns = loadPatterns(patterns);
        this.registry = registry;
        log.info("IllegalContentGuard loaded: {} denied host(s), {} text pattern(s)",
            deniedHosts.size(), textPatterns.size());
    }

    IllegalContentGuard(Set<String> deniedHosts, List<Pattern> textPatterns) {
        this.deniedHosts = deniedHosts;
        this.textPatterns = textPatterns;
        this.registry = new SimpleMeterRegistry();
    }

    @Override
    public ProcessingResult process(Document document) {
        String host = hostOf(document.url());
        if (host != null && deniedHosts.contains(host)) {
            return quarantine(document, "url-denylist");
        }

        String text = document.extractedText();
        if (text != null && !text.isBlank()) {
            for (int i = 0; i < textPatterns.size(); i++) {
                if (textPatterns.get(i).matcher(text).find()) {
                    return quarantine(document, "text-pattern:" + (i + 1));
                }
            }
        }
        return ProcessingResult.success(document);
    }

    private ProcessingResult quarantine(Document document, String reason) {
        String tag = reason.startsWith("text-pattern") ? "text-pattern" : reason;
        registry.counter("content.quarantined.total", "reason", tag).increment();
        return ProcessingResult.halt(document, reason);
    }

    @Override
    public boolean supports(DocumentType type) {
        return type == DocumentType.HTML;
    }

    @Override
    public int order() {
        return 5;
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? null : host.toLowerCase();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Set<String> loadHosts(Resource resource) {
        Set<String> hosts = new LinkedHashSet<>();
        for (String line : readLines(resource)) {
            hosts.add(line.toLowerCase());
        }
        return hosts;
    }

    private static List<Pattern> loadPatterns(Resource resource) {
        List<Pattern> patterns = new ArrayList<>();
        for (String line : readLines(resource)) {
            try {
                patterns.add(Pattern.compile(line, Pattern.CASE_INSENSITIVE));
            } catch (RuntimeException e) {
                log.warn("Skipping invalid illegal-content pattern: {}", line);
            }
        }
        return patterns;
    }

    private static List<String> readLines(Resource resource) {
        List<String> lines = new ArrayList<>();
        if (!resource.exists()) {
            log.warn("Illegal-content resource not found, treating as empty: {}", resource);
            return lines;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
        } catch (IOException e) {
            log.warn("Failed reading illegal-content resource {}, treating as empty", resource, e);
        }
        return lines;
    }
}
