package zelisline.ub.integrations.easytax.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.integrations.easytax.domain.EasyTaxBranchSettings;

public interface EasyTaxBranchSettingsRepository
        extends JpaRepository<EasyTaxBranchSettings, String> {
}
