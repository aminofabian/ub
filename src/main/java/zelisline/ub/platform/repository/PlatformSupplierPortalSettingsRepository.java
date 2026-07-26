package zelisline.ub.platform.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.platform.domain.PlatformSupplierPortalSettings;

public interface PlatformSupplierPortalSettingsRepository
        extends JpaRepository<PlatformSupplierPortalSettings, String> {}
