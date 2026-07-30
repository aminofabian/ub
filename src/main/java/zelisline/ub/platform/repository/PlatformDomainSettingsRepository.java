package zelisline.ub.platform.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.platform.domain.PlatformDomainSettings;

public interface PlatformDomainSettingsRepository
        extends JpaRepository<PlatformDomainSettings, String> {}
