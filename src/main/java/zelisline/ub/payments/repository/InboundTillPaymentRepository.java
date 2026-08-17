package zelisline.ub.payments.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.InboundTillPayment;

public interface InboundTillPaymentRepository extends JpaRepository<InboundTillPayment, String> {

    boolean existsByGatewayTypeAndGatewayEventId(GatewayType gatewayType, String gatewayEventId);

    Optional<InboundTillPayment> findByGatewayTypeAndGatewayEventId(
            GatewayType gatewayType,
            String gatewayEventId
    );

    Optional<InboundTillPayment> findFirstByBusinessIdAndMpesaReceiptIgnoreCaseAndStatus(
            String businessId,
            String mpesaReceipt,
            String status
    );

    Optional<InboundTillPayment> findFirstByBusinessIdAndMpesaReceiptIgnoreCase(
            String businessId,
            String mpesaReceipt
    );

    List<InboundTillPayment> findByBusinessIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
            String businessId,
            String status,
            Instant createdAfter
    );
}
