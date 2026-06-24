package zelisline.ub.audit.repository;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import zelisline.ub.audit.domain.AuditEvent;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, String> {

    @Query("""
            SELECT e FROM AuditEvent e
            WHERE e.businessId = :businessId
              AND (:branchId IS NULL OR e.branchId = :branchId)
              AND (:category IS NULL OR e.category = :category)
              AND (:eventType IS NULL OR e.eventType = :eventType)
              AND (:severity IS NULL OR e.severity = :severity)
              AND (:actorId IS NULL OR e.actorId = :actorId)
              AND (:targetType IS NULL OR e.targetType = :targetType)
              AND (:targetId IS NULL OR e.targetId = :targetId)
              AND (:shiftId IS NULL OR e.shiftId = :shiftId)
              AND (:from IS NULL OR e.createdAt >= :from)
              AND (:to IS NULL OR e.createdAt <= :to)
            ORDER BY e.createdAt DESC
            """)
    Page<AuditEvent> search(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("category") AuditEventCategory category,
            @Param("eventType") String eventType,
            @Param("severity") AuditEventSeverity severity,
            @Param("actorId") String actorId,
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            @Param("shiftId") String shiftId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable
    );
}
