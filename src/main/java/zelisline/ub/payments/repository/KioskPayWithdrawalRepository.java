package zelisline.ub.payments.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.payments.domain.KioskPayWithdrawal;

public interface KioskPayWithdrawalRepository extends JpaRepository<KioskPayWithdrawal, String> {

    Optional<KioskPayWithdrawal> findByBusinessIdAndIdempotencyKey(String businessId, String idempotencyKey);

    Optional<KioskPayWithdrawal> findByKopokopoSendMoneyId(String kopokopoSendMoneyId);

    List<KioskPayWithdrawal> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    List<KioskPayWithdrawal> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    List<KioskPayWithdrawal> findByBusinessIdAndStatusInOrderByCreatedAtAsc(
            String businessId, List<String> statuses);

    List<KioskPayWithdrawal> findByStatusInOrderByCreatedAtAsc(List<String> statuses);

    boolean existsByBusinessIdAndStatusIn(String businessId, List<String> statuses);

    @Query("""
            select coalesce(sum(w.amount), 0) from KioskPayWithdrawal w
            where w.businessId = :businessId
              and w.status = 'SUCCESS'
              and w.completedAt >= :since
            """)
    BigDecimal sumSuccessfulSince(@Param("businessId") String businessId, @Param("since") Instant since);
}
