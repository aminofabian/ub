package zelisline.ub.sales.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import zelisline.ub.sales.domain.Sale;

public interface SaleRepository extends JpaRepository<Sale, String> {

    Optional<Sale> findByBusinessIdAndIdempotencyKey(String businessId, String idempotencyKey);

    Optional<Sale> findByIdAndBusinessId(String id, String businessId);

    List<Sale> findByBusinessIdAndCustomerIdOrderBySoldAtDesc(String businessId, String customerId);

    List<Sale> findByBusinessIdAndCustomerIdOrderBySoldAtDesc(
            String businessId, String customerId, org.springframework.data.domain.Pageable pageable);

    List<Sale> findByBusinessIdAndSoldByOrderBySoldAtDesc(String businessId, String soldBy);

    List<Sale> findByShiftIdAndStatus(String shiftId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sale s where s.id = :id and s.businessId = :businessId")
    Optional<Sale> findByIdAndBusinessIdForUpdate(@Param("id") String id, @Param("businessId") String businessId);

    /**
     * Current highest receipt number for the business (empty when none yet).
     *
     * <p>Locks the max row (and the trailing gap on the
     * {@code (business_id, receipt_no)} index) so concurrent sales for the same
     * business serialize instead of colliding; the unique index is the backstop.
     * Callers add 1 for the next number. A plain (non-aggregate) {@code FOR UPDATE}
     * select is used because MySQL/MariaDB (and H2's MySQL mode) reject
     * {@code FOR UPDATE} on grouped/aggregate queries, and Hibernate 7 forbids
     * {@code @Lock} on native queries.
     */
    @Query(
            value = "SELECT receipt_no FROM sales WHERE business_id = :businessId"
                    + " ORDER BY receipt_no DESC LIMIT 1 FOR UPDATE",
            nativeQuery = true
    )
    Optional<Long> nextReceiptNo(@Param("businessId") String businessId);

    boolean existsByBusinessId(String businessId);
}
