package zelisline.ub.crm.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.api.dto.CrmInboxStatusResponse;
import zelisline.ub.integrations.whatsapp.application.WhatsAppChannelSettingsService;
import zelisline.ub.integrations.whatsapp.domain.WhatsAppChannelRoute;
import zelisline.ub.integrations.whatsapp.domain.WhatsAppChannelRouteStatuses;
import zelisline.ub.integrations.whatsapp.repository.WhatsAppChannelRouteRepository;
import zelisline.ub.platform.application.PlatformIntegrationSettingsService;

/**
 * Explains a quiet merchant inbox: is the inbound channel on, and does this shop have an active
 * number? Read-only and tenant-scoped.
 */
@Service
@RequiredArgsConstructor
public class CrmInboxStatusService {

    private final WhatsAppChannelSettingsService channelSettings;
    private final WhatsAppChannelRouteRepository routeRepository;
    private final PlatformIntegrationSettingsService platformIntegrationSettingsService;

    @Transactional(readOnly = true)
    public CrmInboxStatusResponse status(String businessId) {
        List<WhatsAppChannelRoute> active = routeRepository
                .findByBusinessIdOrderByCreatedAtAsc(businessId).stream()
                .filter(route -> WhatsAppChannelRouteStatuses.ACTIVE.equals(route.getStatus()))
                .toList();
        String number = active.stream()
                .map(WhatsAppChannelRoute::getDisplayNumber)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
        return new CrmInboxStatusResponse(
                channelSettings.inboundEnabled(),
                !active.isEmpty(),
                number,
                platformIntegrationSettingsService.resolveMetaWhatsApp().configured());
    }
}
