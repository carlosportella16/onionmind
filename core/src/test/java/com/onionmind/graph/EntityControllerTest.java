package com.onionmind.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EntityControllerTest {

    private GraphRepository graph;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        graph = mock(GraphRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new EntityController(graph)).build();
    }

    @Test
    void returnsTheEntityWithPagesAndRelated() throws Exception {
        when(graph.find("person:jose silva")).thenReturn(Optional.of(new EntityView("person", "jose silva",
            "José Silva", List.of(new EntityView.PageMention("http://a.onion/", "a.onion", 0.9, 2)),
            List.of(new EntityView.Related("crypto_wallet", "1abc", "1abc", 1)))));

        mvc.perform(get("/api/entities/person/José Silva"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.key").value("jose silva"))
            .andExpect(jsonPath("$.pages[0].host").value("a.onion"))
            .andExpect(jsonPath("$.related[0].sharedPages").value(1));
    }

    @Test
    void unknownEntityIs404() throws Exception {
        when(graph.find("crypto_wallet:nada")).thenReturn(Optional.empty());

        mvc.perform(get("/api/entities/crypto_wallet/nada")).andExpect(status().isNotFound());
        verify(graph).find("crypto_wallet:nada");
    }

    @Test
    void typeOutsideTheTaxonomyIs400WithoutTouchingTheGraph() throws Exception {
        mvc.perform(get("/api/entities/weapon/ak47")).andExpect(status().isBadRequest());
        verifyNoInteractions(graph);
    }

    @Test
    void graphDownIs503() throws Exception {
        when(graph.find("person:ana")).thenThrow(new ServiceUnavailableException("neo4j down"));

        mvc.perform(get("/api/entities/person/Ana")).andExpect(status().isServiceUnavailable());
    }
}
