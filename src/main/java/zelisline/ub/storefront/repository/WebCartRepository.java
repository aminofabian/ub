package zelisline.ub.storefront.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    /**
     * Top items sitting in stale carts — ranked by how many abandoned carts
     * contain them, then by total quantity.
     */
    @Query(value = """
            SELECT l.item_id AS itemId,
                   COALESCE(SUM(l.quantity), 0) AS totalQty,
                   COUNT(DISTINCT l.cart_id) AS cartCount
              FROM web_cart_lines l
              JOIN web_carts c ON c.id = l.cart_id
             WHERE c.business_id = :businessId
               AND c.updated_at < :staleBefore
             GROUP BY l.item_id
             ORDER BY cartCount DESC, totalQty DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<AbandonedItemRow> findTopAbandonedItems(
            @Param("businessId") String businessId,
            @Param("staleBefore") Instant staleBefore,
            @Param("limit") int limit);

    interface AbandonedItemRow {
        String getItemId();

        BigDecimal getTotalQty();

        Number getCartCount();
    }
}
