package zelisline.ub.posdraft.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.posdraft.domain.PosDraftLine;

public interface PosDraftLineRepository extends JpaRepository<PosDraftLine, String> {

    List<PosDraftLine> findByDraftIdOrderByLineIndexAsc(String draftId);

    Optional<PosDraftLine> findByIdAndDraftId(String id, String draftId);

    int countByDraftIdAndDeletedFalse(String draftId);

    /**
     * Services a staff member is doing right now, on any open visit of the business. A locking
     * read, taken under that staff member's row lock, so one person cannot be started twice.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("""
            select l from PosDraftLine l, PosDraft d
             where d.id = l.draftId
               and d.businessId = :businessId
               and d.status = 'pending'
               and l.deleted = false
               and l.lineStage = 'IN_SERVICE'
               and l.performerStaffProfileId = :staffProfileId
            """)
    List<PosDraftLine> findInServiceByPerformer(
            @org.springframework.data.repository.query.Param("businessId") String businessId,
            @org.springframework.data.repository.query.Param("staffProfileId") String staffProfileId);

    /** Services using a resource right now. A locking read, taken under the resource's row lock. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("""
            select l from PosDraftLine l, PosDraft d
             where d.id = l.draftId
               and d.businessId = :businessId
               and d.status = 'pending'
               and l.deleted = false
               and l.lineStage = 'IN_SERVICE'
               and l.resourceId = :resourceId
            """)
    List<PosDraftLine> findInServiceByResource(
            @org.springframework.data.repository.query.Param("businessId") String businessId,
            @org.springframework.data.repository.query.Param("resourceId") String resourceId);

    /** When a resource was last freed, for its cleaning time. */
    @org.springframework.data.jpa.repository.Query("""
            select max(l.finishedAt) from PosDraftLine l
             where l.businessId = :businessId and l.resourceId = :resourceId and l.deleted = false
            """)
    java.time.Instant lastFinishedOnResource(
            @org.springframework.data.repository.query.Param("businessId") String businessId,
            @org.springframework.data.repository.query.Param("resourceId") String resourceId);

    /** Services at a branch that started inside a window, for the calendar. */
    @org.springframework.data.jpa.repository.Query("""
            select l from PosDraftLine l, PosDraft d
             where d.id = l.draftId
               and d.businessId = :businessId
               and d.branchId = :branchId
               and d.draftKind = 'SERVICE'
               and l.deleted = false
               and l.startedAt >= :from and l.startedAt < :to
             order by l.startedAt asc
            """)
    List<PosDraftLine> findStartedBetween(
            @org.springframework.data.repository.query.Param("businessId") String businessId,
            @org.springframework.data.repository.query.Param("branchId") String branchId,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    /** Every service in progress at a branch, to show which resources and people are busy. */
    @org.springframework.data.jpa.repository.Query("""
            select l from PosDraftLine l, PosDraft d
             where d.id = l.draftId
               and d.businessId = :businessId
               and d.branchId = :branchId
               and d.status = 'pending'
               and l.deleted = false
               and l.lineStage = 'IN_SERVICE'
            """)
    List<PosDraftLine> findInServiceAtBranch(
            @org.springframework.data.repository.query.Param("businessId") String businessId,
            @org.springframework.data.repository.query.Param("branchId") String branchId);
}
