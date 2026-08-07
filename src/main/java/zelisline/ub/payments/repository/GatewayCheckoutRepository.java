package zelisline.ub.payments.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payments.domain.GatewayCheckout;
import zelisline.ub.payments.domain.GatewayCheckoutContextType;

public interface GatewayCheckoutRepository extends JpaRepository<GatewayCheckout, String> {

    Optional<GatewayCheckout> findByReference(String reference);

    Optional<GatewayCheckout> findFirstByContextTypeAndContextIdOrderByCreatedAtDesc(
            GatewayCheckoutContextType contextType,
            String contextId
    );

    List<GatewayCheckout> findByStatusAndCreatedAtAfterOrderByCreatedAtAsc(
            String status,
            Instant createdAfter
    );
}
