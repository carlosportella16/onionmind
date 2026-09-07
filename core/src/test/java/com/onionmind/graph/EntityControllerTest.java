package com.onionmind.graph;

import com.onionmind.TestcontainersConfiguration;
import com.onionmind.ai.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** graph.enabled=false by default, so the real bean would be NoOpGraphStore unless overridden below. */
@Import({TestcontainersConfiguration.class, EntityControllerTest.FakeGraphConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class EntityControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String baseUrl() {
        return "http://localhost:" + port + "/api/pages";
    }

    @Test
    void entitiesReturnsWhatTheGraphStoreReports() {
        var response = rest.getForEntity(baseUrl() + "/entities?url=http://example.onion", Entity[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(new Entity(Entity.EntityType.PERSON, "Ana", 0.9));
    }

    @Test
    void blankUrlIsABadRequest() {
        var response = rest.getForEntity(baseUrl() + "/entities?url=", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @TestConfiguration
    static class FakeGraphConfig {
        @Bean
        @Primary
        GraphStore fakeGraphStore() {
            return new GraphStore() {
                @Override
                public boolean hasProcessedContent(String url, String contentHash) {
                    return false;
                }

                @Override
                public void upsertEntities(String pageUrl, String contentHash, List<Entity> entities) {
                }

                @Override
                public List<Entity> findEntitiesByPage(String url) {
                    return List.of(new Entity(Entity.EntityType.PERSON, "Ana", 0.9));
                }
            };
        }
    }
}
