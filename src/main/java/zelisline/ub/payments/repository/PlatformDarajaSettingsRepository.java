package zelisline.ub.payments.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payments.domain.PlatformDarajaSettings;

public interface PlatformDarajaSettingsRepository extends JpaRepository<PlatformDarajaSettings, String> {
}
