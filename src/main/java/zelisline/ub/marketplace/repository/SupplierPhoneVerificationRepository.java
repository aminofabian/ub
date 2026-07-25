package zelisline.ub.marketplace.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.marketplace.domain.SupplierPhoneVerification;

public interface SupplierPhoneVerificationRepository extends JpaRepository<SupplierPhoneVerification, String> {

    Optional<SupplierPhoneVerification> findFirstByPhoneAndConsumedAtIsNullOrderByCreatedAtDesc(String phone);

    List<SupplierPhoneVerification> findByPhoneAndConsumedAtIsNull(String phone);

    Optional<SupplierPhoneVerification> findFirstBySetupTokenHashAndConsumedAtIsNull(String setupTokenHash);
}
