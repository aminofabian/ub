package zelisline.ub.marketplace.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.marketplace.domain.SupplierPortalNotification;

public interface SupplierPortalNotificationRepository extends JpaRepository<SupplierPortalNotification, String> {

    List<SupplierPortalNotification> findByMarketplaceSupplierIdOrderByCreatedAtDesc(
            String marketplaceSupplierId
    );

    long countByMarketplaceSupplierIdAndReadAtIsNull(String marketplaceSupplierId);
}
