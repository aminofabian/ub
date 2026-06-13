package zelisline.ub.grocery.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.grocery.domain.GroceryDraft;

public interface GroceryDraftRepository extends JpaRepository<GroceryDraft, String> {

    Optional<GroceryDraft> findByIdAndBusinessId(String id, String businessId);

    Optional<GroceryDraft> findByBusinessIdAndClientDraftId(String businessId, String clientDraftId);

    Optional<GroceryDraft> findByBusinessIdAndBranchIdAndCreatedByAndStatus(
            String businessId, String branchId, String createdBy, String status);

    @Query("""
            select d from GroceryDraft d
             where d.businessId = :businessId
               and d.branchId = :branchId
               and d.status = :status
               and d.updatedAt >= :since
             order by d.updatedAt desc
            """)
    List<GroceryDraft> findRecentByBranchAndStatus(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            @Param("since") Instant since
    );

    @Query("""
            select d from GroceryDraft d
             where d.businessId = :businessId
               and d.branchId = :branchId
               and d.status = :status
               and d.createdBy = :createdBy
               and d.updatedAt >= :since
             order by d.updatedAt desc
            """)
    List<GroceryDraft> findRecentByBranchStatusAndCreator(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            @Param("createdBy") String createdBy,
            @Param("since") Instant since
    );
}
