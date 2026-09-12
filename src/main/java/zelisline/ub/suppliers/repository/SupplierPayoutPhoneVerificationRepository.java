package zelisline.ub.suppliers.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.suppliers.domain.SupplierPayoutPhoneVerification;

public interface SupplierPayoutPhoneVerificationRepository
        extends JpaRepository<SupplierPayoutPhoneVerification, String> {

    Optional<SupplierPayoutPhoneVerification>
            findFirstByBusinessIdAndSupplierIdAndPhoneAndConsumedAtIsNullOrderByCreatedAtDesc(
                    String businessId, String supplierId, String phone);

    List<SupplierPayoutPhoneVerification> findByBusinessIdAndSupplierIdAndPhoneAndConsumedAtIsNull(
            String businessId, String supplierId, String phone);
}
