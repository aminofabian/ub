package zelisline.ub.integrations.easytax.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.integrations.easytax.domain.PlatformEasyTaxSettings;

public interface PlatformEasyTaxSettingsRepository
        extends JpaRepository<PlatformEasyTaxSettings, String> {
}
