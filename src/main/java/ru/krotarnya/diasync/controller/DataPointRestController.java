package ru.krotarnya.diasync.controller;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
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
    public DeferredResult<List<DataPoint>> getDataPointsLongPoll(
            @RequestParam("userId") String userId,
            @RequestParam("since") Instant since,
            @RequestParam(value = "sinceId", defaultValue = "9223372036854775807") long sinceId,
            @RequestParam(value = "timeoutMs", defaultValue = "75000") long timeoutMs
    ) {
        DeferredResult<List<DataPoint>> result = new DeferredResult<>(timeoutMs);
        AtomicBoolean completionStarted = new AtomicBoolean();
        Runnable unsubscribe = dataPointService.subscribeToDataPointAdded(userId, dataPoint -> {
            if (isAfterCursor(dataPoint, since, sinceId)) {
                completeWithStoredUpdates(result, completionStarted, userId, since, sinceId);
            }
        });

        result.onTimeout(() -> completeWithStoredUpdates(result, completionStarted, userId, since, sinceId));
        result.onError(error -> completionStarted.set(true));
        result.onCompletion(() -> {
            completionStarted.set(true);
            unsubscribe.run();
        });

        try {
            List<DataPoint> storedUpdates = dataPointService.getDataPointsUpdatedAfter(userId, since, sinceId);
            if (!storedUpdates.isEmpty() && completionStarted.compareAndSet(false, true)) {
                result.setResult(storedUpdates);
            }
        } catch (RuntimeException e) {
            completionStarted.set(true);
            unsubscribe.run();
            throw e;
        }

        return result;
    }

    private void completeWithStoredUpdates(
            DeferredResult<List<DataPoint>> result,
            AtomicBoolean completionStarted,
            String userId,
            Instant since,
            long sinceId
    ) {
        if (!completionStarted.compareAndSet(false, true)) {
            return;
        }

        try {
            result.setResult(dataPointService.getDataPointsUpdatedAfter(userId, since, sinceId));
        } catch (RuntimeException e) {
            result.setErrorResult(e);
        }
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
