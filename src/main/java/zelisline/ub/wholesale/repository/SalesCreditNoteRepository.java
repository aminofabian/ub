package zelisline.ub.wholesale.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.wholesale.domain.SalesCreditNote;

public interface SalesCreditNoteRepository extends JpaRepository<SalesCreditNote, String> {

    List<SalesCreditNote> findByInvoiceIdAndBusinessIdOrderByCreatedAtAsc(String invoiceId, String businessId);
}
