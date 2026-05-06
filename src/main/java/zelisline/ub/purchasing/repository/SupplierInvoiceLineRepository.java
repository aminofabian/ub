package zelisline.ub.purchasing.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.purchasing.domain.SupplierInvoiceLine;

public interface SupplierInvoiceLineRepository extends JpaRepository<SupplierInvoiceLine, String> {

    long countByInvoiceId(String invoiceId);

    List<SupplierInvoiceLine> findByInvoiceIdOrderBySortOrderAsc(String invoiceId);
}
