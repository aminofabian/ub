package zelisline.ub.opsalerts.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.opsalerts.domain.OpsAlertPhoneVerification;

public interface OpsAlertPhoneVerificationRepository extends JpaRepository<OpsAlertPhoneVerification, String> {

    Optional<OpsAlertPhoneVerification> findFirstByBusinessIdAndPhoneAndConsumedAtIsNullOrderByCreatedAtDesc(
            String businessId,
            String phone
    );

    List<OpsAlertPhoneVerification> findByBusinessIdAndPhoneAndConsumedAtIsNull(String businessId, String phone);
}
