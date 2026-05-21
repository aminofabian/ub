package zelisline.ub.storefront.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.storefront.domain.WebCart;

public interface WebCartRepository extends JpaRepository<WebCart, String> {

    Optional<WebCart> findByIdAndBusinessId(String id, String businessId);

    @Query(value = """
            SELECT COUNT(DISTINCT c.id)
              FROM web_carts c
             WHERE c.business_id = :businessId
               AND c.updated_at < :staleBefore
               AND EXISTS (
                   SELECT 1 FROM web_cart_lines l WHERE l.cart_id = c.id
               )
            """, nativeQuery = true)
    long countStaleCartsWithItems(
            @Param("businessId") String businessId,
            @Param("staleBefore") Instant staleBefore);
}
