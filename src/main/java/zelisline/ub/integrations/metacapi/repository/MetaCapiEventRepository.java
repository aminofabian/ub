package zelisline.ub.integrations.metacapi.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.integrations.metacapi.domain.MetaCapiEvent;

public interface MetaCapiEventRepository extends JpaRepository<MetaCapiEvent, String> {

    boolean existsByBusinessIdAndEventId(String businessId, String eventId);

    Optional<MetaCapiEvent> findByBusinessIdAndEventId(String businessId, String eventId);

    /**
     * Events due for (re)delivery: never-sent rows plus failed rows that still
     * have retry budget. Auth/config failures are excluded by exhausting their
     * attempt budget (see {@code MetaCapiDeliveryService}).
     */
    @Query("""
            select e from MetaCapiEvent e
            where e.status = 'pending'
               or (e.status = 'failed' and e.attemptCount < :maxAttempts)
            order by e.createdAt asc
            """)
    List<MetaCapiEvent> findDueForDelivery(@Param("maxAttempts") int maxAttempts, Pageable pageable);

    /** Restricted super-admin delivery log query. */
    @Query("""
            select e from MetaCapiEvent e
            where (:businessId is null or e.businessId = :businessId)
              and (:status is null or e.status = :status)
              and (:since is null or e.createdAt >= :since)
            order by e.createdAt desc
            """)
    List<MetaCapiEvent> findForLog(
            @Param("businessId") String businessId,
            @Param("status") String status,
            @Param("since") Instant since,
            Pageable pageable);
}
