package com.onionmind.intelligence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Parameter validation happens before any query. */
class ChangesControllerTest {

    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        mvc = MockMvcBuilders.standaloneSetup(new ChangesController(jdbc,
            Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC))).build();
    }

    @ParameterizedTest
    @CsvSource({
        "since, 2026-10-22T00:00:00Z",   // future
        "since, 2026-09-01T00:00:00Z",   // more than 30 days back
        "since, ontem",                  // not ISO-8601
        "category, armas",               // outside the taxonomy
        "limit, 0"
    })
    void invalidParametersAre400(String name, String value) throws Exception {
        mvc.perform(get("/api/changes").param(name, value)).andExpect(status().isBadRequest());
        verifyNoInteractions(jdbc);
    }
}
