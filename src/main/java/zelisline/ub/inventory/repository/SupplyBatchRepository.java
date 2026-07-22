package zelisline.ub.inventory.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.inventory.domain.SupplyBatch;

public interface SupplyBatchRepository extends JpaRepository<SupplyBatch, String> {

    Optional<SupplyBatch> findByIdAndBusinessId(String id, String businessId);

    List<SupplyBatch> findByBusinessIdAndBranchIdAndStatusOrderByReceivedAtDesc(
            String businessId, String branchId, String status);

    List<SupplyBatch> findByBusinessIdAndSupplierIdAndStatusOrderByReceivedAtDesc(
            String businessId, String supplierId, String status);

    Optional<SupplyBatch> findByBusinessIdAndBatchNumber(String businessId, String batchNumber);

    @Query("""
            select sb from SupplyBatch sb
             where sb.businessId = :businessId
               and (:status is null or sb.status = :status)
               and (:branchId is null or sb.branchId = :branchId)
               and (:supplierId is null or sb.supplierId = :supplierId)
             order by sb.receivedAt desc
            """)
    List<SupplyBatch> findByFilters(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("supplierId") String supplierId,
            @Param("status") String status
    );

    Optional<SupplyBatch> findByBusinessIdAndSourceTypeAndSourceId(
            String businessId, String sourceType, String sourceId);

    /**
     * Prefer this over {@link #findByBusinessIdAndSourceTypeAndSourceId} when duplicates may exist
     * (e.g. after a partial/double Path B post). Ordered oldest-first.
     */
    List<SupplyBatch> findAllByBusinessIdAndSourceTypeAndSourceIdOrderByCreatedAtAscIdAsc(
            String businessId, String sourceType, String sourceId);

    long countByBusinessId(String businessId);

    long countBySupplierIdAndBusinessId(String supplierId, String businessId);

    // ── Revenue aggregation ─────────────────────────────────────────

    @Query("""
            select coalesce(sum(si.lineTotal), 0)
            from SaleItem si
            join InventoryBatch ib on ib.id = si.batchId
            where ib.supplyBatchId = :supplyBatchId
              and ib.businessId = :businessId
            """)
    BigDecimal sumRevenueBySupplyBatchId(
            @Param("supplyBatchId") String supplyBatchId,
            @Param("businessId") String businessId
    );
}
