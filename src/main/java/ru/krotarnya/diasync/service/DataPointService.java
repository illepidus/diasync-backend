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
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import ru.krotarnya.diasync.model.DataPoint;
import ru.krotarnya.diasync.repository.DataPointRepository;

@Service
public final class DataPointService {
    private static final Duration DEFAULT_PERIOD = Duration.ofHours(1);

    private final DataPointRepository dataPointRepository;
    private final UserLockService userLockService;
    private final int readPageSize;
    private final int longPollMaxResults;
    private final Map<String, List<Consumer<DataPoint>>> subscribers = new ConcurrentHashMap<>();

    @Autowired
    public DataPointService(
            DataPointRepository dataPointRepository,
            UserLockService userLockService,
            @Value("${diasync.api.read-page-size}") int readPageSize,
            @Value("${diasync.api.long-poll-max-results}") int longPollMaxResults)
    {
        this.dataPointRepository = dataPointRepository;
        this.userLockService = userLockService;
        this.readPageSize = readPageSize;
        this.longPollMaxResults = longPollMaxResults;
    }

    public List<DataPoint> getDataPointsUpdatedAfter(String userId, Instant after, long afterId) {
        return dataPointRepository.findByUserIdAndUpdateCursorAfter(
                userId,
                after,
                afterId,
                PageRequest.of(0, longPollMaxResults));
    }

    public List<DataPoint> getDataPoints(String userId, @Nullable Instant fromO, @Nullable Instant toO) {
        Instant to = Optional.ofNullable(toO).orElse(Instant.now());
        Instant from = Optional.ofNullable(fromO).orElse(to.minus(DEFAULT_PERIOD));

        return dataPointRepository.findByUserIdAndTimestampBetween(userId, from, to);
    }

    public void forEachDataPoint(
            String userId,
            @Nullable Instant fromO,
            @Nullable Instant toO,
            Consumer<DataPoint> consumer)
    {
        Instant to = Optional.ofNullable(toO).orElse(Instant.now());
        Instant from = Optional.ofNullable(fromO).orElse(to.minus(DEFAULT_PERIOD));
        long afterId = Long.MIN_VALUE;

        while (true) {
            List<DataPoint> page = dataPointRepository.findPageByUserIdAndTimestampBetween(
                    userId,
                    from,
                    to,
                    afterId,
                    PageRequest.of(0, readPageSize));
            page.forEach(consumer);

            if (page.size() < readPageSize) {
                return;
            }
            afterId = page.getLast().getId();
        }
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
                .forEach(p -> subscribers.getOrDefault(p.getUserId(), List.of())
                        .forEach(subscriber -> subscriber.accept(p)));

        return result;
    }

    private Collection<DataPoint> addDataPoints(String userId, List<DataPoint> dataPoints) {
        return dataPointRepository.addDataPoints(userId, dataPoints, userLockService);
    }

    public int truncateDataPoints(String userId) {
        return dataPointRepository.deleteByUserId(userId);
    }

    public Runnable subscribeToDataPointAdded(String userId, Consumer<DataPoint> subscriber) {
        subscribers.compute(userId, (key, currentSubscribers) -> {
            List<Consumer<DataPoint>> updatedSubscribers = currentSubscribers == null
                    ? new CopyOnWriteArrayList<>()
                    : currentSubscribers;
            updatedSubscribers.add(subscriber);
            return updatedSubscribers;
        });

        return () -> subscribers.computeIfPresent(userId, (key, currentSubscribers) -> {
            currentSubscribers.remove(subscriber);
            return currentSubscribers.isEmpty() ? null : currentSubscribers;
        });
    }

    public int deleteByTimestampBefore(Instant before) {
        return dataPointRepository.deleteByTimestampBefore(before);
    }
}
