package zelisline.ub.purchasing.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.purchasing.domain.SupplierInvoice;

public interface SupplierInvoiceRepository extends JpaRepository<SupplierInvoice, String> {

    boolean existsByGoodsReceiptId(String goodsReceiptId);

    Optional<SupplierInvoice> findByIdAndBusinessId(String id, String businessId);

    List<SupplierInvoice> findByBusinessIdAndStatus(String businessId, String status);
}
