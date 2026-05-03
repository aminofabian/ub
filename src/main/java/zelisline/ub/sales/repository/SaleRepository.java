package zelisline.ub.sales.repository;

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sale s where s.id = :id and s.businessId = :businessId")
    Optional<Sale> findByIdAndBusinessIdForUpdate(@Param("id") String id, @Param("businessId") String businessId);
}
