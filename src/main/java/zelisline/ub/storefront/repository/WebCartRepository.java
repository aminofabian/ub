package zelisline.ub.storefront.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.storefront.domain.WebCart;

public interface WebCartRepository extends JpaRepository<WebCart, String> {

    Optional<WebCart> findByIdAndBusinessId(String id, String businessId);
}
