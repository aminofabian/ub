package zelisline.ub.platform.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.platform.domain.PlatformSokoMindSettings;

public interface PlatformSokoMindSettingsRepository
        extends JpaRepository<PlatformSokoMindSettings, String> {}
