package zelisline.ub.integrations.easytax.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.integrations.easytax.domain.EasyTaxSettings;

public interface EasyTaxSettingsRepository extends JpaRepository<EasyTaxSettings, String> {
}
