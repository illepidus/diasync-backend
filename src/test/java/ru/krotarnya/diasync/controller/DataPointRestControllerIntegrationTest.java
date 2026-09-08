package ru.krotarnya.diasync.controller;

import java.time.Instant;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import ru.krotarnya.diasync.model.DataPoint;
import ru.krotarnya.diasync.model.SensorGlucose;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataPointRestControllerIntegrationTest {
    private static final String BASE_URL = "/api/v1";

    @Autowired
    private TestRestTemplate restTemplate;

    private DataPoint createDataPoint() {
        return DataPoint.builder()
                .userId("rest-user")
                .timestamp(Instant.parse("2025-01-01T00:00:00Z"))
                .sensorGlucose(SensorGlucose.builder()
                        .mgdl(120.0)
                        .sensorId("sensor-1")
                        .build())
                .build();
    }

    @BeforeEach
    void clean() {
        restTemplate.exchange(
                BASE_URL + "/truncateDataPoints?userId=rest-user",
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                Void.class);
    }

    @Test
    void shouldAddRetrieveAndTruncateDataPoint() {
        List<DataPoint> payload = List.of(createDataPoint());

        ResponseEntity<DataPoint[]> postResponse = restTemplate.postForEntity(
                BASE_URL + "/addDataPoints",
                payload,
                DataPoint[].class);
        Assertions.assertThat(postResponse.getStatusCode().value()).isEqualTo(200);
        Assertions.assertThat(postResponse.getBody()).isNotNull();
        Assertions.assertThat(postResponse.getBody().length).isEqualTo(1);
        Assertions.assertThat(postResponse.getBody()[0].getId()).isNotNull();

        ResponseEntity<DataPoint[]> getResponse = restTemplate.getForEntity(
                BASE_URL + "/getDataPoints?userId=rest-user&from=2025-01-01T00:00:00Z",
                DataPoint[].class);
        Assertions.assertThat(getResponse.getStatusCode().value()).isEqualTo(200);
        Assertions.assertThat(getResponse.getBody()).isNotNull();
        Assertions.assertThat(getResponse.getBody().length).isEqualTo(1);

        ResponseEntity<Integer> delResponse = restTemplate.exchange(
                BASE_URL + "/truncateDataPoints?userId=rest-user",
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                Integer.class);
        Assertions.assertThat(delResponse.getStatusCode().value()).isEqualTo(200);
        Assertions.assertThat(delResponse.getBody()).isEqualTo(1);
    }

    @Test
    void shouldReturnEmptyListWhenLongPollTimesOut() {
        ResponseEntity<DataPoint[]> response = restTemplate.getForEntity(
                BASE_URL + "/getDataPointsLongPoll?userId=rest-user&since=" + Instant.now()
                        + "&sinceId=0&timeoutMs=10",
                DataPoint[].class);

        Assertions.assertThat(response.getStatusCode().value()).isEqualTo(200);
        Assertions.assertThat(response.getBody()).isEmpty();
    }
}
