package zelisline.ub.messaging.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.messaging.domain.SmsCreditPurchase;
import zelisline.ub.messaging.domain.SmsCreditPurchaseStatus;

public interface SmsCreditPurchaseRepository extends JpaRepository<SmsCreditPurchase, String> {

    Optional<SmsCreditPurchase> findByIdAndBusinessId(String id, String businessId);

    Optional<SmsCreditPurchase> findByStkPushId(String stkPushId);

    Optional<SmsCreditPurchase> findByMpesaReceipt(String mpesaReceipt);

    java.util.List<SmsCreditPurchase> findTop25ByBusinessIdOrderByCreatedAtDesc(String businessId);

    List<SmsCreditPurchase> findByBusinessIdAndStatusOrderByCreatedAtDesc(
            String businessId,
            SmsCreditPurchaseStatus status);
}
