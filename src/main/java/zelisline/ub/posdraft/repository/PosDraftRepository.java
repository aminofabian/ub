package zelisline.ub.posdraft.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.posdraft.domain.PosDraft;

public interface PosDraftRepository extends JpaRepository<PosDraft, String> {

    Optional<PosDraft> findByIdAndBusinessId(String id, String businessId);

    Optional<PosDraft> findByBusinessIdAndClientDraftId(String businessId, String clientDraftId);

    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.branchId = :branchId
               and d.status = :status
               and d.draftKind = 'CART'
               and d.updatedAt >= :since
             order by d.updatedAt desc
            """)
    List<PosDraft> findRecentByBranchAndStatus(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            @Param("since") Instant since
    );

    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.branchId = :branchId
               and d.status = :status
               and d.createdBy = :createdBy
               and d.draftKind = 'CART'
               and d.updatedAt >= :since
             order by d.updatedAt desc
            """)
    List<PosDraft> findRecentByBranchStatusAndCreator(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            @Param("createdBy") String createdBy,
            @Param("since") Instant since
    );

    /** Open walk-in service tickets at a branch, oldest first (the queue). */
    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.branchId = :branchId
               and d.status = 'pending'
               and d.draftKind = 'SERVICE'
             order by d.createdAt asc
            """)
    List<PosDraft> findOpenServiceTickets(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId
    );

    /** Highest ticket number used by the business; the unique key is per business, not per branch. */
    @Query("select coalesce(max(d.ticketNumber), 0) from PosDraft d where d.businessId = :businessId")
    long maxTicketNumber(@Param("businessId") String businessId);

    /**
     * Open tickets being served in a given chair — used to stop two customers sharing one.
     * A locking read on purpose: under InnoDB's default isolation a plain read would use the
     * transaction's earlier snapshot and miss a ticket another till has just put in the chair.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.branchId = :branchId
               and d.status = 'pending'
               and d.draftKind = 'SERVICE'
               and d.serviceStage = 'IN_SERVICE'
               and d.stationNo = :stationNo
            """)
    List<PosDraft> findInServiceAtStation(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("stationNo") int stationNo
    );

    List<PosDraft> findByBusinessIdAndShiftIdAndStatus(
            String businessId,
            String shiftId,
            String status
    );

    /**
     * Untagged pending drafts at a branch updated during a shift window.
     * When {@code createdBy} is null (shared branch shift), all creators match.
     */
    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.branchId = :branchId
               and d.status = :status
               and d.shiftId is null
               and d.updatedAt >= :since
               and (:createdBy is null or d.createdBy = :createdBy)
             order by d.updatedAt desc
            """)
    List<PosDraft> findUntaggedPendingForShiftClose(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            @Param("since") Instant since,
            @Param("createdBy") String createdBy
    );
}
