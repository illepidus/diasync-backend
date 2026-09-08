package ru.krotarnya.diasync.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.krotarnya.diasync.model.DataPoint;
import ru.krotarnya.diasync.model.SensorGlucose;
import ru.krotarnya.diasync.service.DataPointService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Timeout(value = 5, unit = TimeUnit.SECONDS)
class DataPointRestControllerTest {
    private final String userId = UUID.randomUUID().toString();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataPointService service;

    @AfterEach
    void clean() {
        service.truncateDataPoints(userId);
    }

    @Test
    void shouldReturnDataPointAddedWhileLongPollIsWaiting() throws Exception {
        Instant cursor = Instant.now();
        DataPoint dataPoint = createDataPoint(cursor.plusSeconds(1));

        MvcResult pendingRequest = performLongPoll(cursor, 0, 1000);
        service.addDataPoint(dataPoint);

        mockMvc.perform(asyncDispatch(pendingRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].timestamp").value(dataPoint.getTimestamp().toString()));
    }

    @Test
    void shouldContinueAfterIdWhenUpdateTimestampsAreEqual() throws Exception {
        List<DataPoint> stored = service.addDataPoints(List.of(
                createDataPoint(Instant.parse("2025-01-01T00:00:00Z")),
                createDataPoint(Instant.parse("2025-01-01T00:01:00Z"))));
        DataPoint first = stored.getFirst();
        DataPoint second = stored.getLast();

        MvcResult pendingRequest = performLongPoll(first.getUpdateTimestamp(), first.getId(), 1000);

        mockMvc.perform(asyncDispatch(pendingRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(second.getId()))
                .andExpect(jsonPath("$[1]").doesNotExist());
    }

    private MvcResult performLongPoll(Instant since, long sinceId, long timeoutMs) throws Exception {
        return mockMvc.perform(get("/api/v1/getDataPointsLongPoll")
                        .param("userId", userId)
                        .param("since", since.toString())
                        .param("sinceId", Long.toString(sinceId))
                        .param("timeoutMs", Long.toString(timeoutMs)))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private DataPoint createDataPoint(Instant timestamp) {
        return DataPoint.builder()
                .userId(userId)
                .timestamp(timestamp)
                .sensorGlucose(SensorGlucose.builder().mgdl(120.0).sensorId("sensor-1").build())
                .build();
    }
}
