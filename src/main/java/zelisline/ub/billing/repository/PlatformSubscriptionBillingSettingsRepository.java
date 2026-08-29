package zelisline.ub.billing.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.billing.domain.PlatformSubscriptionBillingSettings;

public interface PlatformSubscriptionBillingSettingsRepository
        extends JpaRepository<PlatformSubscriptionBillingSettings, String> {

    Optional<PlatformSubscriptionBillingSettings> findFirstByOrderById();
}
