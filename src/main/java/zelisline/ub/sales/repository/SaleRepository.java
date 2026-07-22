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

    List<Sale> findByBusinessIdAndSoldByOrderBySoldAtDesc(String businessId, String soldBy);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sale s where s.id = :id and s.businessId = :businessId")
    Optional<Sale> findByIdAndBusinessIdForUpdate(@Param("id") String id, @Param("businessId") String businessId);

    /**
     * Next sequential receipt number for the business. FOR UPDATE takes a
     * next-key lock on the (business_id, receipt_no) index so concurrent
     * sales for the same business serialize instead of colliding; the unique
     * index is the backstop.
     */
    @Query(
            value = "SELECT COALESCE(MAX(receipt_no), 0) + 1 FROM sales WHERE business_id = :businessId FOR UPDATE",
            nativeQuery = true
    )
    long nextReceiptNo(@Param("businessId") String businessId);

    boolean existsByBusinessId(String businessId);
}
