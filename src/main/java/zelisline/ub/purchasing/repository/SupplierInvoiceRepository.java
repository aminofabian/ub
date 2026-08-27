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
            WHERE s.deletedAt IS NULL
              AND (
                s.marketplaceSupplierId = :marketplaceSupplierId
                OR EXISTS (
                  SELECT 1 FROM BusinessSupplierConnection c
                  WHERE c.localSupplierId = s.id
                    AND c.marketplaceSupplierId = :marketplaceSupplierId
                    AND c.status = 'active'
                )
              )
            ORDER BY si.invoiceDate DESC, si.createdAt DESC
            """)
    List<SupplierInvoice> findForSupplierPortal(@Param("marketplaceSupplierId") String marketplaceSupplierId);

    boolean existsByGoodsReceiptId(String goodsReceiptId);

    boolean existsByBusinessIdAndInvoiceNumber(String businessId, String invoiceNumber);

    boolean existsByBusinessIdAndInvoiceNumberAndIdNot(String businessId, String invoiceNumber, String id);

    /** Invoice numbers for a shop that start with {@code PB-} (used to allocate the next sequential Path B code). */
    @Query("""
            SELECT si.invoiceNumber FROM SupplierInvoice si
            WHERE si.businessId = :businessId
              AND si.invoiceNumber LIKE 'PB-%'
            """)
    List<String> findPbPrefixedInvoiceNumbers(@Param("businessId") String businessId);

    int countByRawPurchaseSessionId(String rawPurchaseSessionId);

    Optional<SupplierInvoice> findByIdAndBusinessId(String id, String businessId);

    List<SupplierInvoice> findByBusinessIdAndStatus(String businessId, String status);

    boolean existsByBusinessIdAndStatus(String businessId, String status);

    /**
     * Posted supplier invoices for the supplies board (Path B direct receive and Path A GRN bills).
     */
    List<SupplierInvoice> findByBusinessIdAndStatusOrderByCreatedAtDescIdDesc(
            String businessId,
            String status
    );

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
