package zelisline.ub.purchasing.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.purchasing.domain.PurchaseOrder;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, String> {

    Optional<PurchaseOrder> findByIdAndBusinessId(String id, String businessId);

    List<PurchaseOrder> findByBusinessIdAndStatusOrderByCreatedAtDesc(String businessId, String status);

    List<PurchaseOrder> findByBusinessIdAndSupplierIdAndStatusOrderByCreatedAtDesc(
            String businessId, String supplierId, String status);

    /** Several board columns in one query — the buy desk reads sent + drafts together. */
    List<PurchaseOrder> findByBusinessIdAndStatusInOrderByCreatedAtDesc(
            String businessId, List<String> statuses);

    List<PurchaseOrder> findByBusinessIdAndSupplierIdAndStatusInOrderByCreatedAtDesc(
            String businessId, String supplierId, List<String> statuses);

    /** Open proposals a supplier has left with one shop supplier; caps P0.3 order creation. */
    long countByBusinessIdAndSupplierIdAndStatusAndSource(
            String businessId, String supplierId, String status, String source);

    /**
     * Supplier portal inbox. The shop must hold an ACTIVE connection to this passport: a passport tag on
     * the shop's supplier row is not enough on its own (ADR 0038, P0.4). Orders the supplier proposed
     * itself are included while they are still the shop's draft.
     */
    @Query("""
            SELECT po FROM PurchaseOrder po
            JOIN Supplier s ON s.id = po.supplierId
            WHERE (po.sentToSupplierAt IS NOT NULL OR po.source = 'supplier_portal')
              AND po.status <> 'cancelled'
              AND EXISTS (
                SELECT 1 FROM BusinessSupplierConnection c
                WHERE c.businessId = s.businessId
                  AND c.marketplaceSupplierId = :marketplaceSupplierId
                  AND c.status = 'active'
                  AND (c.localSupplierId = s.id OR s.marketplaceSupplierId = :marketplaceSupplierId)
              )
            ORDER BY COALESCE(po.sentToSupplierAt, po.createdAt) DESC
            """)
    List<PurchaseOrder> findSupplierPortalInbox(@Param("marketplaceSupplierId") String marketplaceSupplierId);

    @Query("""
            SELECT po FROM PurchaseOrder po
            JOIN Supplier s ON s.id = po.supplierId
            WHERE po.id = :purchaseOrderId
              AND (po.sentToSupplierAt IS NOT NULL OR po.source = 'supplier_portal')
              AND EXISTS (
                SELECT 1 FROM BusinessSupplierConnection c
                WHERE c.businessId = s.businessId
                  AND c.marketplaceSupplierId = :marketplaceSupplierId
                  AND c.status = 'active'
                  AND (c.localSupplierId = s.id OR s.marketplaceSupplierId = :marketplaceSupplierId)
              )
            """)
    Optional<PurchaseOrder> findSupplierPortalOrder(
            @Param("marketplaceSupplierId") String marketplaceSupplierId,
            @Param("purchaseOrderId") String purchaseOrderId);
}
