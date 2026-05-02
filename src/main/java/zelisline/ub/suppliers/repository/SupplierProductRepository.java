package zelisline.ub.suppliers.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.suppliers.domain.SupplierProduct;

public interface SupplierProductRepository extends JpaRepository<SupplierProduct, String> {

    @Query("""
            SELECT sp FROM SupplierProduct sp
            JOIN Supplier s ON s.id = sp.supplierId
            WHERE sp.itemId = :itemId AND s.businessId = :businessId
              AND sp.deletedAt IS NULL
            ORDER BY sp.primaryLink DESC, s.name ASC
            """)
    List<SupplierProduct> listForItem(@Param("businessId") String businessId, @Param("itemId") String itemId);

    @Query("""
            SELECT sp FROM SupplierProduct sp
            JOIN Supplier s ON s.id = sp.supplierId
            WHERE sp.id = :linkId AND sp.itemId = :itemId AND s.businessId = :businessId
              AND sp.deletedAt IS NULL
            """)
    Optional<SupplierProduct> findLinkForBusiness(
            @Param("businessId") String businessId,
            @Param("itemId") String itemId,
            @Param("linkId") String linkId
    );

    Optional<SupplierProduct> findBySupplierIdAndItemId(String supplierId, String itemId);

    @Query("""
            SELECT COUNT(sp) > 0 FROM SupplierProduct sp
            WHERE sp.itemId = :itemId AND sp.deletedAt IS NULL AND sp.active = TRUE
            """)
    boolean existsActiveByItemId(@Param("itemId") String itemId);

    List<SupplierProduct> findByItemIdAndDeletedAtIsNull(String itemId);
}
