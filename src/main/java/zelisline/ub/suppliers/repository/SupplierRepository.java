package zelisline.ub.suppliers.repository;

import java.util.Optional;

import java.math.BigDecimal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.suppliers.domain.Supplier;

public interface SupplierRepository extends JpaRepository<Supplier, String> {

    Page<Supplier> findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(String businessId, Pageable pageable);

    Optional<Supplier> findByIdAndBusinessIdAndDeletedAtIsNull(String id, String businessId);

    Optional<Supplier> findByBusinessIdAndCodeAndDeletedAtIsNull(String businessId, String code);

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
