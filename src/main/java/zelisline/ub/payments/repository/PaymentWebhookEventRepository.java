package zelisline.ub.payments.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentWebhookEvent;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, String> {

    boolean existsByGatewayTypeAndGatewayEventId(GatewayType gatewayType, String gatewayEventId);
}
