package zelisline.ub.discounts.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.discounts.domain.DiscountExclusion;

public interface DiscountExclusionRepository extends JpaRepository<DiscountExclusion, DiscountExclusion.DiscountExclusionId> {

    List<DiscountExclusion> findByIdDiscountId(String discountId);

    void deleteByIdDiscountId(String discountId);

    @Query("""
            SELECT de FROM DiscountExclusion de
            WHERE de.id.discountId IN :discountIds
            """)
    List<DiscountExclusion> findByDiscountIds(@Param("discountIds") Collection<String> discountIds);
}
