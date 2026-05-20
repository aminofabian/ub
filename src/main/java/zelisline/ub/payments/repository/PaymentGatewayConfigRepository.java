package zelisline.ub.payments.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.PaymentGatewayConfig;

public interface PaymentGatewayConfigRepository extends JpaRepository<PaymentGatewayConfig, String> {

    List<PaymentGatewayConfig> findByBusinessId(String businessId);

    Optional<PaymentGatewayConfig> findByBusinessIdAndGatewayType(String businessId, GatewayType gatewayType);

    List<PaymentGatewayConfig> findByBusinessIdAndStatus(String businessId, GatewayStatus status);

    List<PaymentGatewayConfig> findByBusinessIdAndGatewayTypeAndStatus(
            String businessId, GatewayType gatewayType, GatewayStatus status);

    boolean existsByBusinessIdAndGatewayType(String businessId, GatewayType gatewayType);

    List<PaymentGatewayConfig> findByGatewayTypeAndStatus(GatewayType gatewayType, GatewayStatus status);
}
