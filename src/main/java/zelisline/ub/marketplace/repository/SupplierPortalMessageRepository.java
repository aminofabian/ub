package zelisline.ub.marketplace.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.marketplace.domain.SupplierPortalMessage;

public interface SupplierPortalMessageRepository extends JpaRepository<SupplierPortalMessage, String> {

    List<SupplierPortalMessage> findByMarketplaceSupplierIdOrderByCreatedAtDesc(String marketplaceSupplierId);

    Optional<SupplierPortalMessage> findByContactMessageId(String contactMessageId);

    List<SupplierPortalMessage> findByBusinessIdAndMarketplaceSupplierIdOrderByCreatedAtAsc(
            String businessId,
            String marketplaceSupplierId
    );
}
