package zelisline.ub.payments.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payments.domain.PlatformMpesaCustodySettings;

public interface PlatformMpesaCustodySettingsRepository
        extends JpaRepository<PlatformMpesaCustodySettings, String> {
}
