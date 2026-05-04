package zelisline.ub.reporting.repository;

import java.math.BigDecimal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.reporting.domain.MvInventorySnapshot;

public interface MvInventorySnapshotRepository extends JpaRepository<MvInventorySnapshot, MvInventorySnapshot.Key> {

    interface ValuationRow {
        String getBranchId();

        String getItemId();

        BigDecimal getQtyOnHand();

        BigDecimal getFifoValue();

        java.time.LocalDate getEarliestExpiry();
    }

    @Modifying
    @Query(value = "DELETE FROM mv_inventory_snapshot WHERE business_id = :businessId", nativeQuery = true)
    int deleteForBusiness(@Param("businessId") String businessId);

    @Modifying
    @Query(value = """
            INSERT INTO mv_inventory_snapshot (
                business_id, branch_id, item_id, qty_on_hand, fifo_value, earliest_expiry, refreshed_at)
            SELECT ib.business_id,
                   ib.branch_id,
                   ib.item_id,
                   COALESCE(SUM(ib.quantity_remaining), 0) AS qty_on_hand,
                   COALESCE(SUM(ib.quantity_remaining * ib.unit_cost), 0) AS fifo_value,
                   MIN(CASE WHEN ib.quantity_remaining > 0 AND ib.expiry_date IS NOT NULL THEN ib.expiry_date END)
                       AS earliest_expiry,
                   CURRENT_TIMESTAMP
              FROM inventory_batches ib
             WHERE ib.business_id = :businessId
               AND ib.status = 'active'
             GROUP BY ib.business_id, ib.branch_id, ib.item_id
            """, nativeQuery = true)
    int rebuildForBusiness(@Param("businessId") String businessId);

    @Query(value = """
            SELECT m.branch_id        AS branchId,
                   m.item_id          AS itemId,
                   m.qty_on_hand      AS qtyOnHand,
                   m.fifo_value       AS fifoValue,
                   m.earliest_expiry  AS earliestExpiry
              FROM mv_inventory_snapshot m
             WHERE m.business_id = :businessId
               AND (:branchId IS NULL OR m.branch_id = :branchId)
             ORDER BY m.branch_id, m.item_id
            """, nativeQuery = true)
    java.util.List<ValuationRow> listValuation(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId
    );
}
