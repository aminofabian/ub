package zelisline.ub.purchasing.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
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

    @Query("""
            select b from InventoryBatch b
             where b.businessId = :businessId
               and b.itemId = :itemId
               and b.branchId = :branchId
               and b.status = :status
               and b.quantityRemaining > :minRemaining
               and (b.supplyBatchId is null or b.supplyBatchId not in (
                   select sb.id from zelisline.ub.inventory.domain.SupplyBatch sb where sb.status = 'closed'
               ))
             order by b.id asc
            """)
    List<InventoryBatch> findActiveBatchesForPreview(
            @Param("businessId") String businessId,
            @Param("itemId") String itemId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            @Param("minRemaining") BigDecimal minRemaining
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from InventoryBatch b
             where b.businessId = :businessId
               and b.itemId = :itemId
               and b.branchId = :branchId
               and b.status = :status
               and b.quantityRemaining > :minRemaining
               and (b.supplyBatchId is null or b.supplyBatchId not in (
                   select sb.id from zelisline.ub.inventory.domain.SupplyBatch sb where sb.status = 'closed'
               ))
             order by b.id asc
            """)
    List<InventoryBatch> lockActiveBatchesForPick(
            @Param("businessId") String businessId,
            @Param("itemId") String itemId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            @Param("minRemaining") BigDecimal minRemaining
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from InventoryBatch b
             where b.businessId = :businessId
               and b.branchId = :branchId
               and b.status = :status
               and b.itemId in :itemIds
               and b.quantityRemaining > :minRemaining
               and (b.supplyBatchId is null or b.supplyBatchId not in (
                   select sb.id from zelisline.ub.inventory.domain.SupplyBatch sb where sb.status = 'closed'
               ))
             order by b.itemId asc, b.id asc
            """)
    List<InventoryBatch> lockActiveBatchesForPickForItems(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            @Param("itemIds") Collection<String> itemIds,
            @Param("minRemaining") BigDecimal minRemaining
    );

    @Query("""
            select b from InventoryBatch b
             where b.businessId = :businessId
               and b.branchId = :branchId
               and b.status = :status
               and b.itemId in :itemIds
               and b.quantityRemaining > :minRemaining
               and (b.expiryDate is null or b.expiryDate >= current_date)
             order by b.itemId asc, b.id asc
            """)
    List<InventoryBatch> findActiveBatchesForItems(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            @Param("itemIds") List<String> itemIds,
            @Param("minRemaining") BigDecimal minRemaining
    );

    @Query("""
            select b.itemId, coalesce(sum(b.quantityRemaining), 0)
             from InventoryBatch b
             where b.businessId = :businessId
               and b.branchId = :branchId
               and b.status = :status
               and (b.expiryDate is null or b.expiryDate >= current_date)
             group by b.itemId
            """)
    List<Object[]> sumQuantityRemainingByItemAtBranch(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status
    );

    @Query("""
            select b.itemId, coalesce(sum(b.quantityRemaining), 0)
             from InventoryBatch b
             where b.businessId = :businessId
               and b.branchId = :branchId
               and b.status = :status
               and b.itemId in :itemIds
             group by b.itemId
            """)
    List<Object[]> sumQuantityRemainingForItemsAtBranch(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            @Param("itemIds") Collection<String> itemIds
    );

    @Query("""
            select b.branchId, sum(b.quantityRemaining * b.unitCost)
             from InventoryBatch b, Item i
             where b.businessId = :businessId
               and i.id = b.itemId
               and i.businessId = :businessId
               and i.deletedAt is null
               and b.status = :status
               and b.quantityRemaining > 0
               and (:branchId is null or b.branchId = :branchId)
               and (:itemTypeId is null or i.itemTypeId = :itemTypeId)
             group by b.branchId
             order by b.branchId asc
            """)
    List<Object[]> sumExtensionValueByBranch(
            @Param("businessId") String businessId,
            @Param("status") String status,
            @Param("branchId") String branchId,
            @Param("itemTypeId") String itemTypeId
    );

    @Query("""
            select coalesce(sum(b.quantityRemaining * b.unitCost), 0)
             from InventoryBatch b, Item i
             where b.businessId = :businessId
               and i.id = b.itemId
               and i.businessId = :businessId
               and i.deletedAt is null
               and b.status = :status
               and b.quantityRemaining > 0
               and (:branchId is null or b.branchId = :branchId)
               and (:itemTypeId is null or i.itemTypeId = :itemTypeId)
            """)
    BigDecimal sumTotalExtensionValue(
            @Param("businessId") String businessId,
            @Param("status") String status,
            @Param("branchId") String branchId,
            @Param("itemTypeId") String itemTypeId
    );

    @Query("""
            select b from InventoryBatch b, Item i
             where b.businessId = :businessId
               and i.id = b.itemId
               and i.businessId = :businessId
               and i.deletedAt is null
               and b.status = 'active'
               and b.quantityRemaining > 0
               and b.expiryDate is not null
               and b.expiryDate <= :until
               and (:branchId is null or b.branchId = :branchId)
               and (:itemTypeId is null or i.itemTypeId = :itemTypeId)
             order by b.expiryDate asc, b.id asc
            """)
    List<InventoryBatch> findExpiringOnOrBefore(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("itemTypeId") String itemTypeId,
            @Param("until") LocalDate until
    );

    List<InventoryBatch> findBySupplyBatchId(String supplyBatchId);

    List<InventoryBatch> findBySupplyBatchIdAndStatus(String supplyBatchId, String status);

    List<InventoryBatch> findBySupplyBatchIdAndStatusAndQuantityRemainingGreaterThan(
            String supplyBatchId, String status, BigDecimal minRemaining);

    /** Phase 9: find batches created by a transfer line, filtered by status (e.g. in_transit). */
    List<InventoryBatch> findBySourceTypeAndSourceIdAndStatus(
            String sourceType, String sourceId, String status);

    List<InventoryBatch> findBySourceTypeAndSourceId(String sourceType, String sourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from InventoryBatch b
             where b.businessId = :businessId
               and b.itemId = :itemId
               and b.branchId = :branchId
               and b.status = :status
               and b.quantityRemaining > :minRemaining
               and (b.expiryDate is null or b.expiryDate >= :today)
               and (b.supplyBatchId is null or b.supplyBatchId not in (
                   select sb.id from zelisline.ub.inventory.domain.SupplyBatch sb where sb.status = 'closed'
               ))
             order by b.id asc
            """)
    List<InventoryBatch> lockActiveNonExpiredBatchesForPick(
            @Param("businessId") String businessId,
            @Param("itemId") String itemId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            @Param("minRemaining") BigDecimal minRemaining,
            @Param("today") LocalDate today
    );

    /**
     * Active, on-hand batches for an item (optionally scoped to one branch) — used by the
     * cost-audit tool to rewrite a corrected unit cost across current stock layers.
     */
    @Query("""
            select b from InventoryBatch b
             where b.businessId = :businessId
               and b.itemId = :itemId
               and b.status = 'active'
               and b.quantityRemaining > 0
               and (:branchId is null or b.branchId = :branchId)
             order by b.branchId asc, b.id asc
            """)
    List<InventoryBatch> findActiveBatchesForCostRewrite(
            @Param("businessId") String businessId,
            @Param("itemId") String itemId,
            @Param("branchId") String branchId
    );

    Optional<InventoryBatch> findFirstByBusinessIdAndItemIdAndBranchIdAndStatusOrderByReceivedAtDescIdDesc(
            String businessId,
            String itemId,
            String branchId,
            String status
    );

    /** Most recent batch for COGS reference when overselling (includes fully depleted lines). */
    Optional<InventoryBatch> findFirstByBusinessIdAndItemIdAndBranchIdAndStatusInOrderByReceivedAtDescIdDesc(
            String businessId,
            String itemId,
            String branchId,
            List<String> statuses
    );
}
