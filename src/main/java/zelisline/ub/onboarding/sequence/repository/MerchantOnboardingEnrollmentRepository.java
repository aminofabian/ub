package zelisline.ub.onboarding.sequence.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.onboarding.sequence.domain.MerchantOnboardingEnrollment;

public interface MerchantOnboardingEnrollmentRepository
        extends JpaRepository<MerchantOnboardingEnrollment, String> {

    List<MerchantOnboardingEnrollment> findByMutedAtIsNullAndCompletedAtIsNull(Pageable pageable);
}
