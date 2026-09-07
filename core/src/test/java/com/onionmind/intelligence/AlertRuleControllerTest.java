package com.onionmind.intelligence;

import com.onionmind.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AlertRuleControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String baseUrl() {
        return "http://localhost:" + port + "/api/alert-rules";
    }

    @Test
    void createsAndListsARule() {
        var createRequest = new AlertRuleController.CreateAlertRuleRequest(AlertCriteriaType.KEYWORD, "monero");

        var createResponse = rest.postForEntity(baseUrl(), createRequest, Long.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(createResponse.getBody()).isNotNull();

        var listResponse = rest.getForEntity(baseUrl(), AlertRule[].class);
        assertThat(listResponse.getBody()).extracting(AlertRule::criteriaValue).contains("monero");
    }
}
