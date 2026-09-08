package ru.krotarnya.diasync.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.krotarnya.diasync.model.DataPoint;

interface DataPointRepositoryJpa extends JpaRepository<DataPoint, Long> {
    @Transactional
    int deleteByTimestampBefore(Instant before);

    @Transactional
    int deleteByUserId(String userId);

    Optional<DataPoint> findByUserIdAndTimestamp(String userId, Instant timestamp);

    @Query("""
        SELECT dp FROM DataPoint dp
        WHERE dp.userId = :userId AND dp.timestamp BETWEEN :from AND :to
        UNION
        SELECT dp FROM DataPoint dp
        WHERE dp.userId = :userId AND dp.updateTimestamp BETWEEN :from AND :to
        """)
    List<DataPoint> findByUserIdAndTimestampBetween(
            @Param("userId") String userId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
        SELECT dp FROM DataPoint dp
        WHERE dp.userId = :userId
          AND (dp.timestamp BETWEEN :from AND :to OR dp.updateTimestamp BETWEEN :from AND :to)
          AND dp.id > :afterId
        ORDER BY dp.id ASC
        """)
    List<DataPoint> findPageByUserIdAndTimestampBetween(
            @Param("userId") String userId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("afterId") long afterId,
            Pageable pageable);

    @Query("""
        SELECT dp FROM DataPoint dp
        WHERE dp.userId = :userId
          AND (dp.updateTimestamp > :since OR (dp.updateTimestamp = :since AND dp.id > :sinceId))
        ORDER BY dp.updateTimestamp ASC, dp.id ASC
        """)
    List<DataPoint> findByUserIdAndUpdateCursorAfter(
            @Param("userId") String userId,
            @Param("since") Instant since,
            @Param("sinceId") long sinceId,
            Pageable pageable);
}
