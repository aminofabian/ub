package zelisline.ub.marketplace.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.marketplace.domain.BusinessSupplierConnection;

public interface BusinessSupplierConnectionRepository extends JpaRepository<BusinessSupplierConnection, String> {

    Optional<BusinessSupplierConnection> findByBusinessIdAndMarketplaceSupplierId(
            String businessId, String marketplaceSupplierId);

    boolean existsByBusinessIdAndMarketplaceSupplierId(String businessId, String marketplaceSupplierId);

    List<BusinessSupplierConnection> findByMarketplaceSupplierIdAndStatus(
            String marketplaceSupplierId, String status);

    List<BusinessSupplierConnection> findByMarketplaceSupplierIdOrderByCreatedAtAsc(String marketplaceSupplierId);

    List<BusinessSupplierConnection> findByMarketplaceSupplierIdIn(Collection<String> marketplaceSupplierIds);

    @Query("""
            SELECT COUNT(DISTINCT c.marketplaceSupplierId)
            FROM BusinessSupplierConnection c
            WHERE c.status = :status
            """)
    long countDistinctMarketplaceSuppliersByStatus(@Param("status") String status);

    List<BusinessSupplierConnection> findByBusinessIdAndStatus(String businessId, String status);

    Optional<BusinessSupplierConnection> findByMarketplaceSupplierIdAndLocalSupplierId(
            String marketplaceSupplierId, String localSupplierId);

    Optional<BusinessSupplierConnection> findByLocalSupplierId(String localSupplierId);

    boolean existsByLocalSupplierIdAndStatus(String localSupplierId, String status);

    boolean existsByLocalSupplierId(String localSupplierId);
}
