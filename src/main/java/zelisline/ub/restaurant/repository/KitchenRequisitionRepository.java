package zelisline.ub.restaurant.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import zelisline.ub.restaurant.domain.KitchenRequisition;

public interface KitchenRequisitionRepository extends JpaRepository<KitchenRequisition, String> {

    Optional<KitchenRequisition> findByIdAndBusinessId(String id, String businessId);

    /** Locked, so two people deciding one requisition take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from KitchenRequisition r where r.id = :id and r.businessId = :businessId")
    Optional<KitchenRequisition> findByIdAndBusinessIdForUpdate(
            @Param("id") String id, @Param("businessId") String businessId);

    Optional<KitchenRequisition> findByBusinessIdAndClientKey(String businessId, String clientKey);

    List<KitchenRequisition> findByBusinessIdAndBranchIdAndStatusOrderByRequestedAtAsc(
            String businessId, String branchId, String status);

    /** Decided or cancelled requisitions at a branch since a time, newest first. */
    List<KitchenRequisition> findTop50ByBusinessIdAndBranchIdAndStatusNotAndRequestedAtAfterOrderByRequestedAtDesc(
            String businessId, String branchId, String status, Instant since);

    @Query("select coalesce(max(r.requisitionNo), 0) from KitchenRequisition r"
            + " where r.businessId = :businessId and r.branchId = :branchId")
    long lastNumber(@Param("businessId") String businessId, @Param("branchId") String branchId);

    long countByBusinessIdAndBranchIdAndStatus(String businessId, String branchId, String status);
}
