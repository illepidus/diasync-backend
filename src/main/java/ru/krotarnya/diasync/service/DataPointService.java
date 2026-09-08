package ru.krotarnya.diasync.service;

import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import ru.krotarnya.diasync.model.DataPoint;
import ru.krotarnya.diasync.repository.DataPointRepository;

@Service
public final class DataPointService {
    private static final Duration DEFAULT_PERIOD = Duration.ofHours(1);

    private final DataPointRepository dataPointRepository;
    private final UserLockService userLockService;
    private final Map<String, List<FluxSink<DataPoint>>> subscribers = new ConcurrentHashMap<>();

    @Autowired
    public DataPointService(DataPointRepository dataPointRepository, UserLockService userLockService) {
        this.dataPointRepository = dataPointRepository;
        this.userLockService = userLockService;
    }

    public List<DataPoint> getDataPointsUpdatedAfter(String userId, Instant after, long afterId) {
        return dataPointRepository.findByUserIdAndUpdateCursorAfter(userId, after, afterId);
    }

    public List<DataPoint> getDataPoints(String userId, @Nullable Instant fromO, @Nullable Instant toO) {
        Instant to = Optional.ofNullable(toO).orElse(Instant.now());
        Instant from = Optional.ofNullable(fromO).orElse(to.minus(DEFAULT_PERIOD));

        return dataPointRepository.findByUserIdAndTimestampBetween(userId, from, to);
    }

    public DataPoint addDataPoint(DataPoint dataPoint) {
        return addDataPoints(List.of(dataPoint)).getFirst();
    }

    public List<DataPoint> addDataPoints(List<DataPoint> dataPoints) {
        Instant updateTimestamp = Instant.now();

        List<DataPoint> result = dataPoints.stream()
                .map(DataPoint::withoutIdAndUpdateTimestamp)
                .map(dataPoint -> dataPoint.withUpdateTimestamp(updateTimestamp))
                .collect(Collectors.groupingBy(DataPoint::getUserId))
                .entrySet()
                .stream()
                .map(e -> addDataPoints(e.getKey(), e.getValue()))
                .flatMap(Collection::stream)
                .toList();

        result.stream()
                .filter(p -> updateTimestamp.equals(p.getUpdateTimestamp()))
                .forEach(p -> subscribers.getOrDefault(p.getUserId(), List.of()).forEach(sink -> sink.next(p)));

        return result;
    }

    private Collection<DataPoint> addDataPoints(String userId, List<DataPoint> dataPoints) {
        return dataPointRepository.addDataPoints(userId, dataPoints, userLockService);
    }

    public int truncateDataPoints(String userId) {
        return dataPointRepository.deleteByUserId(userId);
    }

    public Flux<DataPoint> onDataPointAdded(String userId) {
        return Flux.create(sink -> {
            subscribers.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(sink);
            sink.onDispose(() -> Optional.ofNullable(subscribers.get(userId))
                    .filter(sinks -> sinks.remove(sink))
                    .filter(List::isEmpty)
                    .ifPresent(sinks -> subscribers.remove(userId)));
        });
    }

    public int deleteByTimestampBefore(Instant before) {
        return dataPointRepository.deleteByTimestampBefore(before);
    }
}
