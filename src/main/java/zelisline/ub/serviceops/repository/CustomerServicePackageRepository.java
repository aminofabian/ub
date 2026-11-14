package zelisline.ub.serviceops.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import zelisline.ub.serviceops.domain.CustomerServicePackage;

public interface CustomerServicePackageRepository extends JpaRepository<CustomerServicePackage, String> {

    Optional<CustomerServicePackage> findByBusinessIdAndIdempotencyKey(String businessId, String idempotencyKey);

    List<CustomerServicePackage> findByBusinessIdAndCustomerIdOrderByCreatedAtDesc(String businessId, String customerId);

    /** Locked, so two tills cannot use the same last session. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CustomerServicePackage p where p.id = :id and p.businessId = :businessId")
    Optional<CustomerServicePackage> findByIdAndBusinessIdForUpdate(
            @Param("id") String id, @Param("businessId") String businessId);
}
