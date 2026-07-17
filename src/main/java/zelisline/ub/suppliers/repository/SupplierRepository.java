package zelisline.ub.suppliers.repository;

import java.util.List;
import java.util.Optional;

import java.math.BigDecimal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.suppliers.domain.Supplier;

public interface SupplierRepository extends JpaRepository<Supplier, String> {

    @Query("""
            select s from Supplier s
             where s.businessId = :businessId
               and s.deletedAt is null
               and (:q is null or :q = ''
                    or lower(s.name) like lower(concat('%', :q, '%'))
                    or lower(coalesce(s.code, '')) like lower(concat('%', :q, '%'))
                    or exists (
                      select 1 from SupplierProduct sp
                       join Item i on i.id = sp.itemId
                       where sp.supplierId = s.id
                         and sp.deletedAt is null
                         and sp.active = true
                         and i.businessId = s.businessId
                         and i.deletedAt is null
                         and i.active = true
                         and (lower(i.name) like lower(concat('%', :q, '%'))
                           or lower(coalesce(i.variantName, '')) like lower(concat('%', :q, '%'))
                           or lower(i.sku) like lower(concat('%', :q, '%'))
                           or lower(coalesce(i.barcode, '')) like lower(concat('%', :q, '%'))
                           or lower(coalesce(sp.supplierSku, '')) like lower(concat('%', :q, '%')))
                    ))
               and (:status is null or :status = '' or s.status = :status)
             order by s.name asc
            """)
    Page<Supplier> searchSuppliers(
            @Param("businessId") String businessId,
            @Param("q") String q,
            @Param("status") String status,
            Pageable pageable
    );

    Optional<Supplier> findByIdAndBusinessIdAndDeletedAtIsNull(String id, String businessId);

    Optional<Supplier> findByIdAndBusinessId(String id, String businessId);

    Optional<Supplier> findByIdAndDeletedAtIsNull(String id);

    /**
     * Public marketplace directory: active tenant suppliers that have at least one
     * active product link. Cross-tenant by design.
     */
    @Query("""
            SELECT s FROM Supplier s
             WHERE s.deletedAt IS NULL
               AND LOWER(s.status) = 'active'
               AND (s.code IS NULL OR s.code <> 'SYS-UNASSIGNED')
               AND EXISTS (
                 SELECT 1 FROM SupplierProduct sp
                  JOIN Item i ON i.id = sp.itemId
                  WHERE sp.supplierId = s.id
                    AND sp.deletedAt IS NULL
                    AND sp.active = TRUE
                    AND i.deletedAt IS NULL
                    AND i.active = TRUE
               )
               AND (:q IS NULL OR :q = ''
                    OR LOWER(s.name) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.code, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(COALESCE(s.supplierType, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR EXISTS (
                      SELECT 1 FROM SupplierProduct sp2
                       JOIN Item i2 ON i2.id = sp2.itemId
                       WHERE sp2.supplierId = s.id
                         AND sp2.deletedAt IS NULL
                         AND sp2.active = TRUE
                         AND i2.deletedAt IS NULL
                         AND i2.active = TRUE
                         AND (LOWER(i2.name) LIKE LOWER(CONCAT('%', :q, '%'))
                           OR LOWER(COALESCE(i2.barcode, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                           OR LOWER(i2.sku) LIKE LOWER(CONCAT('%', :q, '%'))
                           OR LOWER(COALESCE(sp2.supplierSku, '')) LIKE LOWER(CONCAT('%', :q, '%')))
                    ))
             ORDER BY s.name ASC
            """)
    Page<Supplier> searchPublicDirectory(@Param("q") String q, Pageable pageable);

    Optional<Supplier> findByBusinessIdAndLegacyImportSourceIdAndDeletedAtIsNull(
            String businessId,
            String legacyImportSourceId
    );

    Optional<Supplier> findByBusinessIdAndCodeAndDeletedAtIsNull(String businessId, String code);

    /**
     * Resolve a public marketplace supplier from the hex id prefix used in deterministic slugs.
     */
    @Query("""
            SELECT s FROM Supplier s
             WHERE s.deletedAt IS NULL
               AND LOWER(s.status) = 'active'
               AND (s.code IS NULL OR s.code <> 'SYS-UNASSIGNED')
               AND LOWER(REPLACE(s.id, '-', '')) LIKE CONCAT(LOWER(:idPrefix), '%')
               AND EXISTS (
                 SELECT 1 FROM SupplierProduct sp
                  JOIN Item i ON i.id = sp.itemId
                  WHERE sp.supplierId = s.id
                    AND sp.deletedAt IS NULL
                    AND sp.active = TRUE
                    AND i.deletedAt IS NULL
                    AND i.active = TRUE
               )
            """)
    List<Supplier> findPublicActiveByIdPrefix(@Param("idPrefix") String idPrefix);

    @Query("""
            SELECT COUNT(s) > 0 FROM Supplier s
             WHERE s.businessId = :businessId
               AND LOWER(s.name) = LOWER(:name)
               AND s.deletedAt IS NULL
               AND (:ignoreId IS NULL OR s.id <> :ignoreId)
            """)
    boolean existsDuplicateName(
            @Param("businessId") String businessId,
            @Param("name") String name,
            @Param("ignoreId") String ignoreId
    );

    @Query("select coalesce(sum(s.prepaymentBalance), 0) from Supplier s where s.businessId = :bid and s.deletedAt is null")
    BigDecimal sumPrepaymentBalanceByBusinessId(@Param("bid") String businessId);
}
