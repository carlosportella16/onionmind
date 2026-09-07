package com.onionmind.ingestion;

import com.onionmind.ingestion.internal.PageRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Delegates to {@code PageRepository} — the only class in this module allowed to reach {@code .internal}. */
@Component
class PageContentLookupImpl implements PageContentLookup {

    private final PageRepository repository;

    PageContentLookupImpl(PageRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<String> currentText(String url) {
        return repository.findExtractedText(url);
    }

    @Override
    public Optional<String> previousVersionText(String url, int beforeVersion) {
        return repository.findPreviousVersionExtractedText(url, beforeVersion);
    }

    @Override
    public Optional<String> currentCategory(String url) {
        return repository.findCategory(url);
    }
}
