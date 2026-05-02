package zelisline.ub.catalog.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.catalog.domain.IdempotencyKey;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    Optional<IdempotencyKey> findByBusinessIdAndKeyHashAndRoute(
            String businessId,
            String keyHash,
            String route
    );
}
