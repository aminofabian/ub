package zelisline.ub.purchasing.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.purchasing.domain.SupplierInvoice;

public interface SupplierInvoiceRepository extends JpaRepository<SupplierInvoice, String> {

    /** Read-only supplier portal view: invoices for local suppliers linked to this marketplace supplier. */
    @Query("""
            SELECT si FROM SupplierInvoice si
            JOIN Supplier s ON s.id = si.supplierId
            WHERE s.marketplaceSupplierId = :marketplaceSupplierId
              AND s.deletedAt IS NULL
            ORDER BY si.invoiceDate DESC, si.createdAt DESC
            """)
    List<SupplierInvoice> findForSupplierPortal(@Param("marketplaceSupplierId") String marketplaceSupplierId);

    boolean existsByGoodsReceiptId(String goodsReceiptId);

    boolean existsByBusinessIdAndInvoiceNumber(String businessId, String invoiceNumber);

    boolean existsByBusinessIdAndInvoiceNumberAndIdNot(String businessId, String invoiceNumber, String id);

    int countByRawPurchaseSessionId(String rawPurchaseSessionId);

    Optional<SupplierInvoice> findByIdAndBusinessId(String id, String businessId);

    List<SupplierInvoice> findByBusinessIdAndStatus(String businessId, String status);

    /**
     * Path B (direct) receipts that produced supplier invoices — supplies listing.
     */
    List<SupplierInvoice> findByBusinessIdAndStatusAndRawPurchaseSessionIdIsNotNullOrderByCreatedAtDescIdDesc(
            String businessId,
            String status
    );

    List<SupplierInvoice> findByBusinessIdAndSupplierIdAndStatusOrderByInvoiceDateDescCreatedAtDescIdDesc(
            String businessId,
            String supplierId,
            String status,
            Pageable pageable
    );

    int countByBusinessIdAndSupplierIdAndStatus(String businessId, String supplierId, String status);

    List<SupplierInvoice> findByBusinessIdAndSupplierIdAndStatus(
            String businessId,
            String supplierId,
            String status
    );
}
