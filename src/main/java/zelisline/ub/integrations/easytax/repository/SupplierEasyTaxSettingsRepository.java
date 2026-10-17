package zelisline.ub.integrations.easytax.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.integrations.easytax.domain.SupplierEasyTaxSettings;

public interface SupplierEasyTaxSettingsRepository
        extends JpaRepository<SupplierEasyTaxSettings, String> {
}
