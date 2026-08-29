package zelisline.ub.messaging.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.messaging.domain.PlatformSmsTierAllowance;

public interface PlatformSmsTierAllowanceRepository
        extends JpaRepository<PlatformSmsTierAllowance, String> {

    Optional<PlatformSmsTierAllowance> findByTierCode(String tierCode);

    Optional<PlatformSmsTierAllowance> findByTierCodeAndActiveTrue(String tierCode);

    List<PlatformSmsTierAllowance> findByActiveTrue();
}
