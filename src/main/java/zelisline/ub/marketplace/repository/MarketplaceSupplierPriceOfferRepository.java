package zelisline.ub.marketplace.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.marketplace.domain.MarketplaceSupplierPriceOffer;

public interface MarketplaceSupplierPriceOfferRepository extends JpaRepository<MarketplaceSupplierPriceOffer, String> {

    @Query("""
            SELECT o FROM MarketplaceSupplierPriceOffer o
            WHERE o.productId = :productId
              AND o.available = TRUE
              AND o.effectiveFrom <= :now
              AND (o.effectiveTo IS NULL OR o.effectiveTo > :now)
            ORDER BY o.minQty ASC, o.unitPrice ASC
            """)
    List<MarketplaceSupplierPriceOffer> findCurrentOffers(
            @Param("productId") String productId,
            @Param("now") Instant now);

    List<MarketplaceSupplierPriceOffer> findByProductId(String productId);

    Optional<MarketplaceSupplierPriceOffer> findByIdAndMarketplaceSupplierId(String id, String marketplaceSupplierId);
}
