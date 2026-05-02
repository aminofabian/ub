package zelisline.ub.purchasing.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.purchasing.domain.RawPurchaseSession;

public interface RawPurchaseSessionRepository extends JpaRepository<RawPurchaseSession, String> {

    Optional<RawPurchaseSession> findByIdAndBusinessId(String id, String businessId);
}
