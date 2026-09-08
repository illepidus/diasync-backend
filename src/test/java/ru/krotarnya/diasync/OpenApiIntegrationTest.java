package ru.krotarnya.diasync;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class OpenApiIntegrationTest {
    @Autowired
    private TestRestTemplate restTemplate;

    @Value("${springdoc.api-docs.path}")
    String apiDocsEndpoint;

    @Value("${springdoc.swagger-ui.path}")
    String swaggerUiEndpoint;


    @Test
    void shouldProvideApiDocs() {
        ResponseEntity<String> response = query(apiDocsEndpoint);

        Assertions.assertThat(response.getStatusCode().value())
                .as("API Docs should return 200 OK")
                .isEqualTo(200);

        Assertions.assertThat(response.getBody())
                .as("API Docs response should contain OpenAPI JSON")
                .isNotNull()
                .contains("\"openapi\"")
                .contains("\"paths\"");
    }

    @Test
    void shouldProvideSwaggerUI() {
        ResponseEntity<String> response = query(swaggerUiEndpoint);

        Assertions.assertThat(response.getStatusCode().value())
                .as("Swagger UI should return 200 OK")
                .isEqualTo(200);

        Assertions.assertThat(response.getBody())
                .as("Swagger UI response should contain HTML")
                .isNotNull()
                .contains("<!DOCTYPE html>")
                .contains("Swagger UI");
    }

    @Test
    void shouldProvideKiosk() {
        ResponseEntity<String> response = query("/kiosk");

        Assertions.assertThat(response.getStatusCode().value()).isEqualTo(200);
        Assertions.assertThat(response.getBody())
                .isNotNull()
                .contains("<title>Diasync Kiosk</title>")
                .contains("<canvas id=\"bg\"></canvas>");
    }

    private ResponseEntity<String> query(String endpoint) {
        return restTemplate.exchange(
                endpoint,
                HttpMethod.GET,
                null,
                String.class);
    }
}
