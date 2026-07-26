package zelisline.ub.marketplace.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.marketplace.domain.MarketplaceSupplierProductEditRequest;

public interface MarketplaceSupplierProductEditRequestRepository
        extends JpaRepository<MarketplaceSupplierProductEditRequest, String> {

    List<MarketplaceSupplierProductEditRequest> findByMarketplaceSupplierIdAndStatusOrderByCreatedAtDesc(
            String marketplaceSupplierId,
            String status
    );

    Optional<MarketplaceSupplierProductEditRequest> findFirstByProductIdAndStatusOrderByCreatedAtDesc(
            String productId,
            String status
    );

    List<MarketplaceSupplierProductEditRequest> findByMarketplaceSupplierIdInAndStatusOrderByCreatedAtDesc(
            List<String> marketplaceSupplierIds,
            String status
    );
}
