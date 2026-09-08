package ru.krotarnya.diasync.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.test.StepVerifier;
import ru.krotarnya.diasync.model.DataPoint;
import ru.krotarnya.diasync.model.SensorGlucose;
import ru.krotarnya.diasync.service.DataPointService;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Timeout(value = 5, unit = TimeUnit.SECONDS)
class DataPointRestControllerTest {
    private final String userId = UUID.randomUUID().toString();

    @Autowired
    private DataPointRestController controller;

    @Autowired
    private DataPointService service;

    @AfterEach
    void clean() {
        service.truncateDataPoints(userId);
    }

    @Test
    void shouldReturnDataPointAddedWhileLongPollIsWaiting() {
        Instant cursor = Instant.now();
        DataPoint dataPoint = createDataPoint(cursor.plusSeconds(1));

        StepVerifier.create(controller.getDataPointsLongPoll(userId, cursor, 0, 1000))
                .then(() -> service.addDataPoint(dataPoint))
                .assertNext(points -> {
                    assertEquals(1, points.size());
                    assertEquals(dataPoint.getTimestamp(), points.getFirst().getTimestamp());
                })
                .verifyComplete();
    }

    @Test
    void shouldContinueAfterIdWhenUpdateTimestampsAreEqual() {
        List<DataPoint> stored = service.addDataPoints(List.of(
                createDataPoint(Instant.parse("2025-01-01T00:00:00Z")),
                createDataPoint(Instant.parse("2025-01-01T00:01:00Z"))));
        DataPoint first = stored.getFirst();
        DataPoint second = stored.getLast();

        StepVerifier.create(controller.getDataPointsLongPoll(
                        userId, first.getUpdateTimestamp(), first.getId(), 1000))
                .expectNextMatches(points -> points.size() == 1 && points.getFirst().getId().equals(second.getId()))
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyListWhenLongPollTimesOut() {
        StepVerifier.create(controller.getDataPointsLongPoll(userId, Instant.now(), 0, 10))
                .expectNext(List.of())
                .verifyComplete();
    }

    private DataPoint createDataPoint(Instant timestamp) {
        return DataPoint.builder()
                .userId(userId)
                .timestamp(timestamp)
                .sensorGlucose(SensorGlucose.builder().mgdl(120.0).sensorId("sensor-1").build())
                .build();
    }
}
