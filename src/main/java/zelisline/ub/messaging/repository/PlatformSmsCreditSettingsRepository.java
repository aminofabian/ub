package zelisline.ub.messaging.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.messaging.domain.PlatformSmsCreditSettings;

public interface PlatformSmsCreditSettingsRepository
        extends JpaRepository<PlatformSmsCreditSettings, String> {

    Optional<PlatformSmsCreditSettings> findFirstByOrderById();
}
