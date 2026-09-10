package zelisline.ub.platform.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.platform.domain.PlatformAuthSettings;

public interface PlatformAuthSettingsRepository
        extends JpaRepository<PlatformAuthSettings, String> {}
