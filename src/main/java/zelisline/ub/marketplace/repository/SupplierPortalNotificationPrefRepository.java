package zelisline.ub.marketplace.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.marketplace.domain.SupplierPortalNotificationPref;

public interface SupplierPortalNotificationPrefRepository
        extends JpaRepository<SupplierPortalNotificationPref, String> {

    Optional<SupplierPortalNotificationPref> findBySupplierUserId(String supplierUserId);
}
