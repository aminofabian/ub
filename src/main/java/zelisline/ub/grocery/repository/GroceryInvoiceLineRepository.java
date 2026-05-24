package zelisline.ub.grocery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.grocery.domain.GroceryInvoiceLine;

public interface GroceryInvoiceLineRepository extends JpaRepository<GroceryInvoiceLine, String> {

    List<GroceryInvoiceLine> findByInvoiceIdOrderByLineIndex(String invoiceId);
}
