package zelisline.ub.purchasing.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import zelisline.ub.purchasing.domain.RawPurchaseSession;

public interface RawPurchaseSessionRepository extends JpaRepository<RawPurchaseSession, String> {

    Optional<RawPurchaseSession> findByIdAndBusinessId(String id, String businessId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM RawPurchaseSession s WHERE s.id = :id AND s.businessId = :businessId")
    Optional<RawPurchaseSession> findByIdAndBusinessIdForUpdate(
            @Param("id") String id,
            @Param("businessId") String businessId
    );

    List<RawPurchaseSession> findByBusinessIdAndStatusOrderByCreatedAtDesc(String businessId, String status);

    List<RawPurchaseSession> findByBusinessIdAndSupplierIdAndStatusOrderByCreatedAtDesc(
            String businessId, String supplierId, String status);
}
