package zelisline.ub.integrations.whatsapp.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.integrations.whatsapp.domain.WhatsAppChannelRoute;

public interface WhatsAppChannelRouteRepository extends JpaRepository<WhatsAppChannelRoute, String> {

    Optional<WhatsAppChannelRoute> findByPhoneNumberId(String phoneNumberId);

    List<WhatsAppChannelRoute> findByBusinessIdOrderByCreatedAtAsc(String businessId);

    /** Routes that ride their own Meta app (Model B) — widens webhook verification. */
    List<WhatsAppChannelRoute> findByOwnCredentialsTrue();

    boolean existsByPhoneNumberId(String phoneNumberId);
}
