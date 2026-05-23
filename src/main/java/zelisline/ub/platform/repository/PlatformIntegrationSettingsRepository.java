package zelisline.ub.platform.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.platform.domain.PlatformIntegrationSettings;

public interface PlatformIntegrationSettingsRepository
        extends JpaRepository<PlatformIntegrationSettings, String> {}
