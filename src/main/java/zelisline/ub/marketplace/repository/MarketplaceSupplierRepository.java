package zelisline.ub.marketplace.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.marketplace.domain.MarketplaceSupplier;

public interface MarketplaceSupplierRepository extends JpaRepository<MarketplaceSupplier, String> {

    /** The passport of a supplier that has a shop in this tenant. Null when it has none. */
    Optional<MarketplaceSupplier> findFirstByBusinessId(String businessId);

    @Query("""
            SELECT m FROM MarketplaceSupplier m
            WHERE (:status IS NULL OR m.status = :status)
              AND (
                :q IS NULL
                OR LOWER(m.name) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(m.contactEmail, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(m.contactPhone, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(m.username, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(m.supplierNumber, '')) LIKE LOWER(CONCAT('%', :q, '%'))
              )
            """)
    Page<MarketplaceSupplier> search(
            @Param("q") String q,
            @Param("status") String status,
            Pageable pageable);

    long countByStatus(String status);

    /**
     * Public directory: listed, active passports with at least one active product. Never reads a
     * shop's own supplier rows. Pass "" for no search text.
     */
    @Query("""
            SELECT m FROM MarketplaceSupplier m
            WHERE m.listed = TRUE
              AND m.status = 'active'
              AND EXISTS (
                SELECT 1 FROM MarketplaceSupplierProduct p0
                WHERE p0.marketplaceSupplierId = m.id
                  AND p0.status = 'active'
              )
              AND (:q = ''
                   OR LOWER(m.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(m.username, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(m.supplierNumber, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR EXISTS (
                     SELECT 1 FROM MarketplaceSupplierProduct p
                     WHERE p.marketplaceSupplierId = m.id
                       AND p.status = 'active'
                       AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
                         OR LOWER(COALESCE(p.barcode, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                         OR LOWER(COALESCE(p.sku, '')) LIKE LOWER(CONCAT('%', :q, '%')))
                   ))
            ORDER BY m.name ASC
            """)
    Page<MarketplaceSupplier> searchPublicDirectory(@Param("q") String q, Pageable pageable);

    @Query("""
            SELECT m FROM MarketplaceSupplier m
            WHERE m.listed = TRUE
              AND m.status = 'active'
              AND m.id LIKE CONCAT(:idPrefix, '%')
            """)
    java.util.List<MarketplaceSupplier> findListedActiveByIdPrefix(@Param("idPrefix") String idPrefix);

    Optional<MarketplaceSupplier> findByIdAndStatus(String id, String status);

    Optional<MarketplaceSupplier> findByUsernameIgnoreCase(String username);

    Optional<MarketplaceSupplier> findFirstByContactPhoneOrderByCreatedAtAsc(String contactPhone);

    Optional<MarketplaceSupplier> findFirstByContactEmailIgnoreCaseOrderByCreatedAtAsc(String contactEmail);

    @Query("""
            SELECT m FROM MarketplaceSupplier m
            WHERE (
                (m.contactPhone IS NOT NULL AND (
                    m.contactPhone = :phone
                    OR m.contactPhone = :altPhone
                    OR m.contactPhone LIKE CONCAT('%', :phoneTail)
                ))
                OR (m.altContactPhone IS NOT NULL AND (
                    m.altContactPhone = :phone
                    OR m.altContactPhone = :altPhone
                    OR m.altContactPhone LIKE CONCAT('%', :phoneTail)
                ))
            )
            ORDER BY m.createdAt ASC
            """)
    java.util.List<MarketplaceSupplier> findByContactPhoneVariants(
            @Param("phone") String phone,
            @Param("altPhone") String altPhone,
            @Param("phoneTail") String phoneTail);

    Optional<MarketplaceSupplier> findBySupplierNumber(String supplierNumber);

    /** The passport bound to this shop, when the shop is a supplier's own (ADR 0038). */
    Optional<MarketplaceSupplier> findFirstByBusinessId(String businessId);

    boolean existsByUsernameIgnoreCase(String username);
}
