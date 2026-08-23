package zelisline.ub.purchasing.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.purchasing.domain.PurchaseOrderLine;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, String> {

    List<PurchaseOrderLine> findByPurchaseOrderIdOrderBySortOrderAscIdAsc(String purchaseOrderId);

    @Query("select coalesce(max(l.sortOrder), -1) from PurchaseOrderLine l where l.purchaseOrderId = :poId")
    int maxSortOrder(@Param("poId") String purchaseOrderId);

    /**
     * Open (draft/sent) Path A inbound qty per item at a branch — {@code qty_ordered − qty_received}
     * summed across open POs. Used by the nightly restock engine's {@code inbound} input.
     */
    interface OpenInboundRow {
        String getItemId();

        BigDecimal getQty();
    }

    @Query("""
            SELECT l.itemId AS itemId, COALESCE(SUM(l.qtyOrdered - l.qtyReceived), 0) AS qty
              FROM PurchaseOrderLine l
              JOIN PurchaseOrder po ON po.id = l.purchaseOrderId
             WHERE po.businessId = :businessId
               AND po.branchId = :branchId
               AND po.status IN ('draft', 'sent')
             GROUP BY l.itemId
            """)
    List<OpenInboundRow> sumOpenInboundByItem(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId
    );
}
