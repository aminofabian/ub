package zelisline.ub.wholesale.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.wholesale.domain.SalesReceipt;

public interface SalesReceiptRepository extends JpaRepository<SalesReceipt, String> {

    List<SalesReceipt> findByInvoiceIdAndBusinessIdOrderByCreatedAtAsc(String invoiceId, String businessId);
}
