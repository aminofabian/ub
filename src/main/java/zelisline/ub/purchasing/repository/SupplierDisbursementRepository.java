package zelisline.ub.purchasing.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.purchasing.domain.SupplierDisbursement;

public interface SupplierDisbursementRepository extends JpaRepository<SupplierDisbursement, String> {

    Optional<SupplierDisbursement> findFirstByBusinessIdAndSupplierInvoiceIdAndStatusOrderByCreatedAtDesc(
            String businessId,
            String supplierInvoiceId,
            String status
    );

    Optional<SupplierDisbursement> findByKopokopoSendMoneyId(String kopokopoSendMoneyId);

    List<SupplierDisbursement> findByBusinessIdAndSupplierInvoiceIdOrderByCreatedAtDesc(
            String businessId,
            String supplierInvoiceId
    );
}
