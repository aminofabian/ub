package zelisline.ub.marketplace.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.marketplace.domain.SupplierIdentityIndex;

public interface SupplierIdentityIndexRepository extends JpaRepository<SupplierIdentityIndex, String> {

    Optional<SupplierIdentityIndex> findBySupplierId(String supplierId);

    Optional<SupplierIdentityIndex> findByMarketplaceSupplierIdAndSupplierIdIsNull(String marketplaceSupplierId);

    @Query("""
            SELECT s FROM SupplierIdentityIndex s
            WHERE s.businessId = :businessId
              AND s.taxIdNormalized IS NOT NULL
              AND s.taxIdNormalized = :taxId
            """)
    List<SupplierIdentityIndex> findOwnBusinessByTaxId(
            @Param("businessId") String businessId,
            @Param("taxId") String taxId);

    @Query("""
            SELECT s FROM SupplierIdentityIndex s
            WHERE s.businessId = :businessId
              AND s.phoneNormalized IS NOT NULL
              AND s.phoneNormalized = :phone
            """)
    List<SupplierIdentityIndex> findOwnBusinessByPhone(
            @Param("businessId") String businessId,
            @Param("phone") String phone);

    @Query("""
            SELECT s FROM SupplierIdentityIndex s
            WHERE s.marketplaceSupplierId IS NOT NULL
              AND s.taxIdNormalized IS NOT NULL
              AND s.taxIdNormalized = :taxId
            """)
    List<SupplierIdentityIndex> findMarketplaceByTaxId(@Param("taxId") String taxId);

    @Query("""
            SELECT s FROM SupplierIdentityIndex s
            WHERE s.marketplaceSupplierId IS NOT NULL
              AND s.phoneNormalized IS NOT NULL
              AND s.phoneNormalized = :phone
            """)
    List<SupplierIdentityIndex> findMarketplaceByPhone(@Param("phone") String phone);

    @Query("""
            SELECT s FROM SupplierIdentityIndex s
            WHERE s.businessId = :businessId
              AND s.emailNormalized IS NOT NULL
              AND s.emailNormalized = :email
            """)
    List<SupplierIdentityIndex> findOwnBusinessByEmail(
            @Param("businessId") String businessId,
            @Param("email") String email);

    @Query("""
            SELECT s FROM SupplierIdentityIndex s
            WHERE s.marketplaceSupplierId IS NOT NULL
              AND s.emailNormalized IS NOT NULL
              AND s.emailNormalized = :email
            """)
    List<SupplierIdentityIndex> findMarketplaceByEmail(@Param("email") String email);

    @Query("""
            SELECT s FROM SupplierIdentityIndex s
            WHERE s.businessId = :businessId
              AND s.nameNormalized LIKE CONCAT(:namePrefix, '%')
            """)
    List<SupplierIdentityIndex> findOwnBusinessByNamePrefix(
            @Param("businessId") String businessId,
            @Param("namePrefix") String namePrefix);

    @Query("""
            SELECT s FROM SupplierIdentityIndex s
            WHERE s.marketplaceSupplierId IS NOT NULL
              AND s.nameNormalized LIKE CONCAT(:namePrefix, '%')
            """)
    List<SupplierIdentityIndex> findMarketplaceByNamePrefix(@Param("namePrefix") String namePrefix);
}
