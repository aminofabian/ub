package zelisline.ub.marketplace.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.marketplace.domain.MarketplaceSupplierProduct;

public interface MarketplaceSupplierProductRepository extends JpaRepository<MarketplaceSupplierProduct, String> {

    @Query("""
            SELECT p FROM MarketplaceSupplierProduct p
            JOIN MarketplaceSupplier s ON s.id = p.marketplaceSupplierId
            WHERE s.status = 'active'
              AND p.status = 'active'
              AND (:q IS NULL OR :q = ''
                   OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(p.barcode, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(p.sku, '')) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY p.name ASC
            """)
    Page<MarketplaceSupplierProduct> searchPublic(
            @Param("q") String q,
            Pageable pageable);

    @Query("""
            SELECT p FROM MarketplaceSupplierProduct p
            WHERE p.marketplaceSupplierId = :supplierId
              AND (:status IS NULL OR p.status = :status)
              AND (:q IS NULL OR :q = ''
                   OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(p.barcode, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(p.sku, '')) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY p.name ASC
            """)
    Page<MarketplaceSupplierProduct> searchForSupplier(
            @Param("supplierId") String supplierId,
            @Param("q") String q,
            @Param("status") String status,
            Pageable pageable);

    Optional<MarketplaceSupplierProduct> findByIdAndMarketplaceSupplierId(String id, String marketplaceSupplierId);

    List<MarketplaceSupplierProduct> findByMarketplaceSupplierIdAndStatus(
            String marketplaceSupplierId, String status);
}
