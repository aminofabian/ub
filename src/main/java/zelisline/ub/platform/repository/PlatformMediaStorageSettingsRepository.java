package zelisline.ub.platform.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.platform.domain.PlatformMediaStorageSettings;

public interface PlatformMediaStorageSettingsRepository
        extends JpaRepository<PlatformMediaStorageSettings, String> {}
