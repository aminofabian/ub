package zelisline.ub.credits.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.credits.domain.CustomerPhoneVerification;

public interface CustomerPhoneVerificationRepository extends JpaRepository<CustomerPhoneVerification, String> {

    Optional<CustomerPhoneVerification> findFirstByBusinessIdAndPhoneAndConsumedAtIsNullOrderByCreatedAtDesc(
            String businessId,
            String phone
    );

    Optional<CustomerPhoneVerification> findFirstByBusinessIdAndRegistrationTokenHashAndConsumedAtIsNull(
            String businessId,
            String registrationTokenHash
    );

    List<CustomerPhoneVerification> findByBusinessIdAndPhoneAndConsumedAtIsNull(String businessId, String phone);
}
