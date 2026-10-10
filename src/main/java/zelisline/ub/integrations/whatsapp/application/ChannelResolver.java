package zelisline.ub.integrations.whatsapp.application;

import java.util.Optional;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import zelisline.ub.integrations.whatsapp.domain.WhatsAppChannelRoute;
import zelisline.ub.integrations.whatsapp.domain.WhatsAppChannelRouteStatuses;
import zelisline.ub.integrations.whatsapp.repository.WhatsAppChannelRouteRepository;

/**
 * Resolves which shop a Meta {@code phone_number_id} belongs to.
 *
 * <p>The routing seam for inbound ingest ({@code docs/scopes/whatsapp-crm/SCOPE.md} §6.4).
 * Read-only: an unmapped or paused number resolves to empty so ingest leaves the message
 * unrouted for super-admin triage rather than guessing a tenant.
 *
 * <p>No side effects and no dependency on the platform Meta keys — this only answers
 * "whose number is this?".
 */
@Service
@RequiredArgsConstructor
public class ChannelResolver {

    private final WhatsAppChannelRouteRepository routeRepository;

    @Transactional(readOnly = true)
    public Optional<String> businessIdForPhoneNumber(String phoneNumberId) {
        if (phoneNumberId == null || phoneNumberId.isBlank()) {
            return Optional.empty();
        }
        return routeRepository.findByPhoneNumberId(phoneNumberId.trim())
                .filter(route -> WhatsAppChannelRouteStatuses.ACTIVE.equals(route.getStatus()))
                .map(WhatsAppChannelRoute::getBusinessId);
    }

    /**
     * The shop's {@code phone_number_id} used as the outbound "from" when a conversation has no
     * bound number. Resolves only when the shop has exactly one <em>active</em> route; zero or many
     * is ambiguous and resolves to empty so the sender falls back to the platform number.
     */
    @Transactional(readOnly = true)
    public Optional<String> activePhoneNumberIdForBusiness(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return Optional.empty();
        }
        List<WhatsAppChannelRoute> active = routeRepository
                .findByBusinessIdOrderByCreatedAtAsc(businessId).stream()
                .filter(route -> WhatsAppChannelRouteStatuses.ACTIVE.equals(route.getStatus()))
                .toList();
        return active.size() == 1 ? Optional.of(active.get(0).getPhoneNumberId()) : Optional.empty();
    }
}
