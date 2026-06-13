package zelisline.ub.posdraft.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
}
