package zelisline.ub.platform.pageseal.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.platform.pageseal.domain.PageSealUnlock;

public interface PageSealUnlockRepository extends JpaRepository<PageSealUnlock, String> {

    Optional<PageSealUnlock> findByTokenHashAndExpiresAtAfter(String tokenHash, Instant now);
}
