package zelisline.ub.payments.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payments.domain.GatewayStkPush;
import zelisline.ub.payments.domain.GatewayType;

public interface GatewayStkPushRepository extends JpaRepository<GatewayStkPush, String> {

    Optional<GatewayStkPush> findByGatewayTypeAndGatewayCheckoutId(
            GatewayType gatewayType,
            String gatewayCheckoutId
    );

    Optional<GatewayStkPush> findFirstByBusinessIdAndMerchantReferenceAndStatus(
            String businessId,
            String merchantReference,
            String status
    );

    List<GatewayStkPush> findByStatusAndCreatedAtAfterOrderByCreatedAtAsc(
            String status,
            Instant createdAfter
    );

    Optional<GatewayStkPush> findFirstByContextTypeAndContextIdOrderByCreatedAtDesc(
            zelisline.ub.payments.domain.StkPushContextType contextType,
            String contextId
    );
}
