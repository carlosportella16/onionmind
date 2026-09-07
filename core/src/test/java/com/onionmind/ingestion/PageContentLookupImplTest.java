package com.onionmind.ingestion;

import com.onionmind.ingestion.internal.PageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PageContentLookupImplTest {

    @Mock
    private PageRepository repository;

    @Test
    void currentTextDelegatesToRepository() {
        when(repository.findExtractedText("http://example.onion")).thenReturn(Optional.of("current text"));

        var lookup = new PageContentLookupImpl(repository);

        assertThat(lookup.currentText("http://example.onion")).contains("current text");
    }

    @Test
    void previousVersionTextDelegatesToRepository() {
        when(repository.findPreviousVersionExtractedText("http://example.onion", 3)).thenReturn(Optional.of("v2 text"));

        var lookup = new PageContentLookupImpl(repository);

        assertThat(lookup.previousVersionText("http://example.onion", 3)).contains("v2 text");
    }

    @Test
    void emptyWhenRepositoryHasNoText() {
        when(repository.findExtractedText("http://missing.onion")).thenReturn(Optional.empty());

        var lookup = new PageContentLookupImpl(repository);

        assertThat(lookup.currentText("http://missing.onion")).isEmpty();
    }

    @Test
    void currentCategoryDelegatesToRepository() {
        when(repository.findCategory("http://example.onion")).thenReturn(Optional.of("forum"));

        var lookup = new PageContentLookupImpl(repository);

        assertThat(lookup.currentCategory("http://example.onion")).contains("forum");
    }
}
