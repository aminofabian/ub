package zelisline.ub.pharmacy.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import zelisline.ub.purchasing.domain.StockMovement;

/** Read-only sums for the ordering forecast. Every query is scoped to one business. */
public interface OrderingRepository extends Repository<StockMovement, String> {

    /** Rows of (itemId, branchId, sum of quantity delta) for sales and their reversals. */
    @Query("""
            select m.itemId, m.branchId, sum(m.quantityDelta)
              from StockMovement m
             where m.businessId = :businessId
               and m.movementType in :types
               and m.createdAt >= :since
             group by m.itemId, m.branchId
            """)
    List<Object[]> soldSince(
            @Param("businessId") String businessId,
            @Param("types") Collection<String> types,
            @Param("since") Instant since);

    /** Rows of (itemId, branchId, expiryDate, sum of quantity remaining, max unit cost) for stock on sale. */
    @Query("""
            select b.itemId, b.branchId, b.expiryDate, sum(b.quantityRemaining), max(b.unitCost)
              from zelisline.ub.purchasing.domain.InventoryBatch b
             where b.businessId = :businessId
               and b.status = 'active'
               and b.quantityRemaining > 0
             group by b.itemId, b.branchId, b.expiryDate
            """)
    List<Object[]> stockOnSale(@Param("businessId") String businessId);

    /** Rows of (itemId, branchId, quantity still to arrive) on orders not yet received or cancelled. */
    @Query("""
            select l.itemId, po.branchId, sum(l.qtyOrdered - l.qtyReceived)
              from zelisline.ub.purchasing.domain.PurchaseOrderLine l
              join zelisline.ub.purchasing.domain.PurchaseOrder po on po.id = l.purchaseOrderId
             where po.businessId = :businessId
               and po.status in :openStatuses
               and l.qtyOrdered > l.qtyReceived
             group by l.itemId, po.branchId
            """)
    List<Object[]> onOrder(
            @Param("businessId") String businessId,
            @Param("openStatuses") Collection<String> openStatuses);
}
