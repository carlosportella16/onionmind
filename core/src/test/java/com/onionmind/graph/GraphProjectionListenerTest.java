package com.onionmind.graph;

import com.onionmind.content.EntityType;
import com.onionmind.ingestion.PageEnrichedEvent;
import com.onionmind.ingestion.PageQuarantinedEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GraphProjectionListenerTest {

    private final GraphRepository graph = mock(GraphRepository.class);
    private final GraphProjectionListener listener = new GraphProjectionListener(graph);

    @Test
    void enrichedPageIsProjected() {
        var event = new PageEnrichedEvent(1, "http://a.onion/", "a.onion", 2, "h", "forum", 0.9, List.of());

        listener.on(event);

        verify(graph).project(event);
    }

    @Test
    void quarantinedPageIsRemoved() {
        listener.on(new PageQuarantinedEvent("http://a.onion/", "a.onion"));

        verify(graph).removePage("http://a.onion/");
    }

    @Test
    void labelsArePascalCaseOfTheType() {
        assertThat(GraphRepository.label(EntityType.CRYPTO_WALLET)).isEqualTo("CryptoWallet");
        assertThat(GraphRepository.label(EntityType.PGP_FINGERPRINT)).isEqualTo("PgpFingerprint");
        assertThat(GraphRepository.label(EntityType.PERSON)).isEqualTo("Person");
    }
}
