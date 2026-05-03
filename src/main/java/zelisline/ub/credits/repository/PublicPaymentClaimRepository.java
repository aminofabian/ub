package zelisline.ub.credits.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.credits.domain.PublicPaymentClaim;

public interface PublicPaymentClaimRepository extends JpaRepository<PublicPaymentClaim, String> {

    Optional<PublicPaymentClaim> findByTokenHash(String tokenHash);

    List<PublicPaymentClaim> findByBusinessIdAndStatusOrderByCreatedAtAsc(String businessId, String status);

    Optional<PublicPaymentClaim> findFirstByBusinessIdAndCreditAccountIdAndStatusInOrderByCreatedAtDesc(
            String businessId,
            String creditAccountId,
            List<String> statuses
    );
}
