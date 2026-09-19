package zelisline.ub.payments.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payments.domain.PlatformCustodySettlement;

public interface PlatformCustodySettlementRepository extends JpaRepository<PlatformCustodySettlement, String> {

    Optional<PlatformCustodySettlement> findByStkPushId(String stkPushId);

    Optional<PlatformCustodySettlement> findByDisbursementId(String disbursementId);

    List<PlatformCustodySettlement> findByStatusAndCreatedAtBefore(String status, Instant createdBefore);
}
