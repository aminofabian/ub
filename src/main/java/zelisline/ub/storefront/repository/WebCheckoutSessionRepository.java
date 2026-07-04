package zelisline.ub.storefront.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.storefront.domain.WebCheckoutSession;

public interface WebCheckoutSessionRepository extends JpaRepository<WebCheckoutSession, String> {

    Optional<WebCheckoutSession> findByBusinessIdAndCartId(String businessId, String cartId);
}
