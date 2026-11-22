package zelisline.ub.marketplace.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.marketplace.domain.MarketplaceBuyerSession;

public interface MarketplaceBuyerSessionRepository extends JpaRepository<MarketplaceBuyerSession, String> {

    Optional<MarketplaceBuyerSession> findByTokenHash(String tokenHash);

    void deleteByTokenHash(String tokenHash);
}
