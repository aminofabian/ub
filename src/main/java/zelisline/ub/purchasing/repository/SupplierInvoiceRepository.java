package zelisline.ub.purchasing.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.purchasing.domain.SupplierInvoice;

public interface SupplierInvoiceRepository extends JpaRepository<SupplierInvoice, String> {

    boolean existsByGoodsReceiptId(String goodsReceiptId);

    boolean existsByBusinessIdAndInvoiceNumberAndIdNot(String businessId, String invoiceNumber, String id);

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
