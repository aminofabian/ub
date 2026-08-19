package zelisline.ub.discounts.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.discounts.domain.DiscountItem;

public interface DiscountItemRepository extends JpaRepository<DiscountItem, DiscountItem.DiscountItemId> {

    List<DiscountItem> findByIdDiscountId(String discountId);

    void deleteByIdDiscountId(String discountId);

    @Query("""
            SELECT di FROM DiscountItem di
            WHERE di.id.discountId IN :discountIds
            """)
    List<DiscountItem> findByDiscountIds(@Param("discountIds") Collection<String> discountIds);

    @Query("""
            SELECT COUNT(di) FROM DiscountItem di
            WHERE di.id.discountId = :discountId
            """)
    long countByDiscountId(@Param("discountId") String discountId);
}
