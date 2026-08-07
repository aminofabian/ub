package zelisline.ub.payments.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payments.domain.SupplierPayoutSettings;

public interface SupplierPayoutSettingsRepository extends JpaRepository<SupplierPayoutSettings, String> {

    List<SupplierPayoutSettings> findByEnabledTrueAndAutoPayEnabledTrue();
}
