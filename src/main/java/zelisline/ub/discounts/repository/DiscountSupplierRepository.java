package zelisline.ub.discounts.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.discounts.domain.DiscountSupplier;

public interface DiscountSupplierRepository
        extends JpaRepository<DiscountSupplier, DiscountSupplier.DiscountSupplierId> {

    List<DiscountSupplier> findByIdDiscountId(String discountId);

    void deleteByIdDiscountId(String discountId);

    @Query("""
            SELECT ds FROM DiscountSupplier ds
            WHERE ds.id.discountId IN :discountIds
            """)
    List<DiscountSupplier> findByDiscountIds(@Param("discountIds") Collection<String> discountIds);
}

