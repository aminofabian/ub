package zelisline.ub.payments.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payments.domain.PlatformKioskPaySettings;

public interface PlatformKioskPaySettingsRepository extends JpaRepository<PlatformKioskPaySettings, String> {
}
