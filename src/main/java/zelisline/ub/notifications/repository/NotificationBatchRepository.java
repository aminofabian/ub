package zelisline.ub.notifications.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.notifications.domain.NotificationBatch;

public interface NotificationBatchRepository extends JpaRepository<NotificationBatch, String> {

    @Query("""
        select b from NotificationBatch b
         where b.businessId = :businessId
           and b.batchKey = :batchKey
           and b.flushedAt is null
           and b.windowEnd > :now
         order by b.windowEnd desc
        """)
    Optional<NotificationBatch> findOpenBatch(
            @Param("businessId") String businessId,
            @Param("batchKey") String batchKey,
            @Param("now") Instant now);

    @Query("""
        select b from NotificationBatch b
         where b.flushedAt is null
           and b.windowEnd <= :now
         order by b.windowEnd asc
        """)
    List<NotificationBatch> findDueForFlush(@Param("now") Instant now, org.springframework.data.domain.Pageable pageable);
}
