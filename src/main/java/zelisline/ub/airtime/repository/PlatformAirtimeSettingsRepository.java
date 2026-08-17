package zelisline.ub.airtime.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.airtime.domain.PlatformAirtimeSettings;

public interface PlatformAirtimeSettingsRepository extends JpaRepository<PlatformAirtimeSettings, String> {
}
