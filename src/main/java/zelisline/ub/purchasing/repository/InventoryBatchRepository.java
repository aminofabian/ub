package zelisline.ub.purchasing.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import zelisline.ub.purchasing.domain.InventoryBatch;

public interface InventoryBatchRepository extends JpaRepository<InventoryBatch, String> {

    Optional<InventoryBatch> findByIdAndBusinessId(String id, String businessId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from InventoryBatch b
             where b.id = :id and b.businessId = :businessId
            """)
    Optional<InventoryBatch> findByIdAndBusinessIdForUpdate(
            @Param("id") String id,
            @Param("businessId") String businessId
    );

    List<InventoryBatch> findByBusinessIdAndItemIdAndBranchIdAndStatusAndQuantityRemainingGreaterThanOrderByIdAsc(
            String businessId,
            String itemId,
            String branchId,
            String status,
            BigDecimal minRemaining
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from InventoryBatch b
             where b.businessId = :businessId
               and b.itemId = :itemId
               and b.branchId = :branchId
               and b.status = :status
               and b.quantityRemaining > :minRemaining
             order by b.id asc
            """)
    List<InventoryBatch> lockActiveBatchesForPick(
            @Param("businessId") String businessId,
            @Param("itemId") String itemId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            @Param("minRemaining") BigDecimal minRemaining
    );

    @Query("""
            select b.itemId, coalesce(sum(b.quantityRemaining), 0)
             from InventoryBatch b
             where b.businessId = :businessId
               and b.branchId = :branchId
               and b.status = :status
             group by b.itemId
            """)
    List<Object[]> sumQuantityRemainingByItemAtBranch(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status
    );

    @Query("""
            select b.branchId, sum(b.quantityRemaining * b.unitCost)
             from InventoryBatch b
             where b.businessId = :businessId
               and b.status = :status
               and b.quantityRemaining > 0
               and (:branchId is null or b.branchId = :branchId)
             group by b.branchId
             order by b.branchId asc
            """)
    List<Object[]> sumExtensionValueByBranch(
            @Param("businessId") String businessId,
            @Param("status") String status,
            @Param("branchId") String branchId
    );

    @Query("""
            select coalesce(sum(b.quantityRemaining * b.unitCost), 0)
             from InventoryBatch b
             where b.businessId = :businessId
               and b.status = :status
               and b.quantityRemaining > 0
               and (:branchId is null or b.branchId = :branchId)
            """)
    BigDecimal sumTotalExtensionValue(
            @Param("businessId") String businessId,
            @Param("status") String status,
            @Param("branchId") String branchId
    );
}
