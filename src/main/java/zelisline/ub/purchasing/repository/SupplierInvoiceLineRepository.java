package zelisline.ub.purchasing.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.purchasing.domain.SupplierInvoiceLine;

public interface SupplierInvoiceLineRepository extends JpaRepository<SupplierInvoiceLine, String> {
}
