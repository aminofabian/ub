package zelisline.ub.marketplace.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.marketplace.domain.SupplierPortalMessage;

public interface SupplierPortalMessageRepository extends JpaRepository<SupplierPortalMessage, String> {

    List<SupplierPortalMessage> findByMarketplaceSupplierIdOrderByCreatedAtDesc(String marketplaceSupplierId);

    List<SupplierPortalMessage> findByBusinessIdAndMarketplaceSupplierIdOrderByCreatedAtAsc(
            String businessId,
            String marketplaceSupplierId
    );
}
