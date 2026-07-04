package zelisline.ub.inventory.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.inventory.domain.StockTakeSession;

public interface StockTakeSessionRepository extends JpaRepository<StockTakeSession, String> {

    @Query("""
            select distinct s from StockTakeSession s
             left join fetch s.lines
             where s.id = :id and s.businessId = :businessId
            """)
    Optional<StockTakeSession> findByIdAndBusinessIdFetchLines(
            @Param("id") String id,
            @Param("businessId") String businessId
    );

    @Query("""
            select s from StockTakeSession s
             left join fetch s.lines
             where s.businessId = :businessId
               and s.branchId = :branchId
               and s.sessionDate = :sessionDate
               and s.status = 'in_progress'
             order by s.createdAt desc
            """)
    List<StockTakeSession> findActiveListByBusinessIdAndBranchIdAndDateFetchLines(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("sessionDate") LocalDate sessionDate
    );

    @Query("""
            select s from StockTakeSession s
             left join fetch s.lines
             where s.businessId = :businessId
               and s.branchId = :branchId
               and s.sessionDate = :sessionDate
               and s.status = 'in_progress'
               and s.sessionType = :sessionType
             order by s.createdAt desc
            """)
    Optional<StockTakeSession> findActiveByBusinessIdAndBranchIdAndDateAndTypeFetchLines(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("sessionDate") LocalDate sessionDate,
            @Param("sessionType") String sessionType
    );

    // Returns a list (not Optional) to avoid NonUniqueResultException when multiple
    // stale sessions exist. Service takes the first (most recent) one.
    @Query("""
            select s from StockTakeSession s
             left join fetch s.lines
             where s.businessId = :businessId
               and s.branchId = :branchId
               and s.status = 'in_progress'
               and s.sessionDate < :today
             order by s.sessionDate desc
            """)
    List<StockTakeSession> findStaleListByBusinessIdAndBranchIdFetchLines(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("today") LocalDate today
    );

    boolean existsByBusinessIdAndBranchIdAndSessionTypeAndSessionDate(
            String businessId, String branchId, String sessionType, LocalDate sessionDate);

    // Find all sessions of a given type for a branch/date (supports multiple sessions per day).
    @Query("""
            select s from StockTakeSession s
             left join fetch s.lines
             where s.businessId = :businessId
               and s.branchId = :branchId
               and s.sessionType = :sessionType
               and s.sessionDate = :sessionDate
             order by s.createdAt desc
            """)
    List<StockTakeSession> findAllByTypeAndBranchAndDateFetchLines(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("sessionType") String sessionType,
            @Param("sessionDate") LocalDate sessionDate
    );

    // Single unified query — handles all filter combinations including date range.
    @Query("""
            select s from StockTakeSession s
             left join fetch s.lines
             where s.businessId = :businessId
               and (:branchId is null or s.branchId = :branchId)
               and (:status   is null or s.status   = :status)
               and (:from     is null or s.sessionDate >= :from)
               and (:to       is null or s.sessionDate <= :to)
             order by s.sessionDate desc, s.createdAt desc
            """)
    List<StockTakeSession> findFiltered(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    // Returns MAX(sessionNumber)+1 for the business so new sessions get the next number.
    // COALESCE handles the case where no sessions exist yet (returns 1).
    @Query("select coalesce(max(s.sessionNumber), 0) + 1 from StockTakeSession s where s.businessId = :businessId")
    int nextSessionNumber(@Param("businessId") String businessId);

    @Query("""
            select s from StockTakeSession s
             left join fetch s.lines
             where s.businessId = :businessId
               and s.branchId = :branchId
               and s.sessionDate = :sessionDate
               and s.sessionType = :sessionType
               and s.source = :source
               and s.dailyAuditId = :dailyAuditId
               and s.status = 'in_progress'
             order by s.createdAt desc
            """)
    Optional<StockTakeSession> findActiveDailyAuditSessionFetchLines(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("sessionDate") LocalDate sessionDate,
            @Param("sessionType") String sessionType,
            @Param("source") String source,
            @Param("dailyAuditId") String dailyAuditId
    );

    @Query("""
            select s from StockTakeSession s
             left join fetch s.lines
             where s.businessId = :businessId
               and s.dailyAuditId = :dailyAuditId
             order by s.sessionType asc, s.createdAt desc
            """)
    List<StockTakeSession> findByDailyAuditIdAndBusinessIdFetchLines(
            @Param("businessId") String businessId,
            @Param("dailyAuditId") String dailyAuditId
    );
}
