package ru.krotarnya.diasync.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import ru.krotarnya.diasync.model.DataPoint;
import ru.krotarnya.diasync.service.DataPointService;

@RestController
public final class DataPointRestController extends RestApiController {
    private final DataPointService dataPointService;

    @Autowired
    public DataPointRestController(DataPointService dataPointService) {
        this.dataPointService = dataPointService;
    }

    @GetMapping("getDataPointsLongPoll")
    public Mono<List<DataPoint>> getDataPointsLongPoll(
            @RequestParam("userId") String userId,
            @RequestParam("since") Instant since,
            @RequestParam(value = "sinceId", defaultValue = "9223372036854775807") long sinceId,
            @RequestParam(value = "timeoutMs", defaultValue = "75000") long timeoutMs
    ) {
        Duration timeout = Duration.ofMillis(timeoutMs);
        Mono<Boolean> storedUpdates = Mono.fromSupplier(
                        () -> dataPointService.getDataPointsUpdatedAfter(userId, since, sinceId))
                .filter(points -> !points.isEmpty())
                .map(points -> true);
        Mono<Boolean> liveUpdate = dataPointService.onDataPointAdded(userId)
                .filter(dp -> isAfterCursor(dp, since, sinceId))
                .next()
                .map(dp -> true);

        return liveUpdate.mergeWith(storedUpdates)
                .next()
                .timeout(timeout, Mono.just(false))
                .then(Mono.fromSupplier(
                        () -> dataPointService.getDataPointsUpdatedAfter(userId, since, sinceId)));
    }

    private boolean isAfterCursor(DataPoint dataPoint, Instant since, long sinceId) {
        Instant updateTimestamp = dataPoint.getUpdateTimestamp();
        if (updateTimestamp == null) {
            return false;
        }

        int timestampComparison = updateTimestamp.compareTo(since);
        return timestampComparison > 0
                || timestampComparison == 0 && dataPoint.getId() != null && dataPoint.getId() > sinceId;
    }

    @GetMapping("getDataPoints")
    public List<DataPoint> getDataPoints(
            @RequestParam("userId") String userId,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to)
    {
        return dataPointService.getDataPoints(userId, from, to);
    }

    @PostMapping("addDataPoints")
    public List<DataPoint> addDataPoints(@RequestBody List<DataPoint> dataPoints) {
        return dataPointService.addDataPoints(dataPoints);
    }

    @DeleteMapping("truncateDataPoints")
    public int truncateDataPoints(@RequestParam("userId") String userId) {
        return dataPointService.truncateDataPoints(userId);
    }
}
