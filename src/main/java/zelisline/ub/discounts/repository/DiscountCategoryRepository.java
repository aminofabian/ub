package zelisline.ub.discounts.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.discounts.domain.DiscountCategory;

public interface DiscountCategoryRepository extends JpaRepository<DiscountCategory, DiscountCategory.DiscountCategoryId> {

    List<DiscountCategory> findByIdDiscountId(String discountId);

    void deleteByIdDiscountId(String discountId);

    @Query("""
            SELECT dc FROM DiscountCategory dc
            WHERE dc.id.discountId IN :discountIds
            """)
    List<DiscountCategory> findByDiscountIds(@Param("discountIds") Collection<String> discountIds);
}
