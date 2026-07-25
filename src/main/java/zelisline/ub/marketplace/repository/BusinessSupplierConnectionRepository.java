package zelisline.ub.marketplace.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.marketplace.domain.BusinessSupplierConnection;

public interface BusinessSupplierConnectionRepository extends JpaRepository<BusinessSupplierConnection, String> {

    Optional<BusinessSupplierConnection> findByBusinessIdAndMarketplaceSupplierId(
            String businessId, String marketplaceSupplierId);

    boolean existsByBusinessIdAndMarketplaceSupplierId(String businessId, String marketplaceSupplierId);

    List<BusinessSupplierConnection> findByMarketplaceSupplierIdAndStatus(
            String marketplaceSupplierId, String status);

    Optional<BusinessSupplierConnection> findByMarketplaceSupplierIdAndLocalSupplierId(
            String marketplaceSupplierId, String localSupplierId);

    boolean existsByLocalSupplierIdAndStatus(String localSupplierId, String status);
}
