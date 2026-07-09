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

    @Query("""
            SELECT po FROM PurchaseOrder po
            JOIN Supplier s ON s.id = po.supplierId
            WHERE s.marketplaceSupplierId = :marketplaceSupplierId
              AND po.sentToSupplierAt IS NOT NULL
              AND po.status <> 'cancelled'
            ORDER BY po.sentToSupplierAt DESC
            """)
    List<PurchaseOrder> findSupplierPortalInbox(@Param("marketplaceSupplierId") String marketplaceSupplierId);

    @Query("""
            SELECT po FROM PurchaseOrder po
            JOIN Supplier s ON s.id = po.supplierId
            WHERE po.id = :purchaseOrderId
              AND s.marketplaceSupplierId = :marketplaceSupplierId
              AND po.sentToSupplierAt IS NOT NULL
            """)
    Optional<PurchaseOrder> findSupplierPortalOrder(
            @Param("marketplaceSupplierId") String marketplaceSupplierId,
            @Param("purchaseOrderId") String purchaseOrderId);
}
