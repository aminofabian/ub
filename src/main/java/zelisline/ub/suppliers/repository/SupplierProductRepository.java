package zelisline.ub.suppliers.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.suppliers.domain.SupplierProduct;

public interface SupplierProductRepository extends JpaRepository<SupplierProduct, String> {

    /**
     * Public marketplace product search across all tenant supplier catalogues.
     */
    @Query("""
            SELECT sp FROM SupplierProduct sp
            JOIN Supplier s ON s.id = sp.supplierId
            JOIN Item i ON i.id = sp.itemId
            WHERE s.deletedAt IS NULL
              AND LOWER(s.status) = 'active'
              AND (s.code IS NULL OR s.code <> 'SYS-UNASSIGNED')
              AND sp.deletedAt IS NULL
              AND sp.active = TRUE
              AND i.deletedAt IS NULL
              AND i.active = TRUE
              AND (:q IS NULL OR :q = ''
                   OR LOWER(i.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(i.variantName, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(i.barcode, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(i.sku) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(sp.supplierSku, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(s.name) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY i.name ASC
            """)
    Page<SupplierProduct> searchPublicDirectory(@Param("q") String q, Pageable pageable);

    @Query("""
            SELECT sp FROM SupplierProduct sp
            JOIN Item i ON i.id = sp.itemId
            WHERE sp.supplierId = :supplierId
              AND sp.deletedAt IS NULL
              AND sp.active = TRUE
              AND i.deletedAt IS NULL
              AND i.active = TRUE
            ORDER BY i.name ASC
            """)
    List<SupplierProduct> listActivePublicForSupplier(@Param("supplierId") String supplierId);

    @Query("""
            SELECT COUNT(sp) FROM SupplierProduct sp
            JOIN Item i ON i.id = sp.itemId
            WHERE sp.supplierId = :supplierId
              AND sp.deletedAt IS NULL
              AND sp.active = TRUE
              AND i.deletedAt IS NULL
              AND i.active = TRUE
            """)
    long countActivePublicForSupplier(@Param("supplierId") String supplierId);

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

    @Query("""
            SELECT sp FROM SupplierProduct sp
            JOIN Supplier s ON s.id = sp.supplierId
            JOIN Item i ON i.id = sp.itemId
            WHERE sp.supplierId = :supplierId AND s.businessId = :businessId
              AND sp.deletedAt IS NULL AND i.deletedAt IS NULL
            ORDER BY i.name ASC, sp.primaryLink DESC
            """)
    List<SupplierProduct> listForSupplier(
            @Param("businessId") String businessId,
            @Param("supplierId") String supplierId
    );
}
