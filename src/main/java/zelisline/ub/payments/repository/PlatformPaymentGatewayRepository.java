package zelisline.ub.payments.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PlatformPaymentGateway;

public interface PlatformPaymentGatewayRepository extends JpaRepository<PlatformPaymentGateway, GatewayType> {

    List<PlatformPaymentGateway> findByIsEnabledTrueOrderBySortOrderAsc();
}
