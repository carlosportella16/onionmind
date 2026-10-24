package com.onionmind.ingestion;

import com.onionmind.content.EntityType;
import com.onionmind.content.ExtractedEntity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageEnrichedEventTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void roundTripsThroughJsonLikeTheEventPublicationRegistryStoresIt() {
        var event = new PageEnrichedEvent(7L, "http://a.onion/x", "a.onion", 2, "hash", "forum", 0.8,
            List.of(ExtractedEntity.of(EntityType.PERSON, "José Silva", 0.9)));

        String json = mapper.writeValueAsString(event);

        assertThat(mapper.readValue(json, PageEnrichedEvent.class)).isEqualTo(event);
        assertThat(mapper.readValue(mapper.writeValueAsString(new PageQuarantinedEvent("http://b.onion/", "b.onion")),
            PageQuarantinedEvent.class)).isEqualTo(new PageQuarantinedEvent("http://b.onion/", "b.onion"));
    }

    @Test
    void hostOfToleratesGarbage() {
        assertThat(PageEnrichedEvent.hostOf("http://abc.onion/path?q=1")).isEqualTo("abc.onion");
        assertThat(PageEnrichedEvent.hostOf("not a url ^^")).isNull();
    }

    @Test
    void nullEntitiesBecomeEmpty() {
        assertThat(new PageEnrichedEvent(1, "u", "h", 1, "x", null, 0, null).entities()).isEmpty();
    }
}
