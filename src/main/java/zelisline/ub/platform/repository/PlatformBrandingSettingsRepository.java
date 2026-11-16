package zelisline.ub.platform.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.platform.domain.PlatformBrandingSettings;

public interface PlatformBrandingSettingsRepository
        extends JpaRepository<PlatformBrandingSettings, String> {}
