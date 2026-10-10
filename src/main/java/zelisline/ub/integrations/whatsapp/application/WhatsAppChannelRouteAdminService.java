package zelisline.ub.integrations.whatsapp.application;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

import zelisline.ub.integrations.whatsapp.api.dto.WhatsAppChannelDtos.NumberRow;
import zelisline.ub.integrations.whatsapp.api.dto.WhatsAppChannelDtos.NumbersResponse;
import zelisline.ub.integrations.whatsapp.api.dto.WhatsAppChannelDtos.RouteNumberRequest;
import zelisline.ub.integrations.whatsapp.domain.WhatsAppChannelRoute;
import zelisline.ub.integrations.whatsapp.domain.WhatsAppChannelRouteStatuses;
import zelisline.ub.integrations.whatsapp.repository.WhatsAppChannelRouteRepository;
import zelisline.ub.platform.application.PlatformIntegrationSettingsService;
import zelisline.ub.platform.application.ResolvedMetaWhatsAppConfig;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Super-admin operations for WhatsApp number → shop routing. Meta credentials themselves are
 * managed via {@code /api/v1/super-admin/platform/integrations}; this only assigns numbers.
 *
 * <p>See {@code docs/scopes/whatsapp-crm/SCOPE.md} §6.2 and §8.
 */
@Service
@RequiredArgsConstructor
public class WhatsAppChannelRouteAdminService {

    private final WhatsAppChannelRouteRepository routeRepository;
    private final BusinessRepository businessRepository;
    private final PlatformIntegrationSettingsService platformIntegrationSettingsService;

    @Transactional(readOnly = true)
    public NumbersResponse getForSuperAdmin() {
        List<WhatsAppChannelRoute> routes = routeRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        Map<String, String> names = businessNames(routes);
        ResolvedMetaWhatsAppConfig meta = platformIntegrationSettingsService.resolveMetaWhatsApp();
        List<NumberRow> rows = routes.stream()
                .map(route -> toRow(route, names.get(route.getBusinessId())))
                .toList();
        return new NumbersResponse(meta.configured(), meta.phoneNumberId(), rows);
    }

    /** Create or re-assign the route for {@code phoneNumberId}. */
    @Transactional
    public NumberRow route(String phoneNumberId, RouteNumberRequest body) {
        String pnid = requirePhoneNumberId(phoneNumberId);
        Business business = businessRepository.findByIdAndDeletedAtIsNull(body.businessId().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));

        WhatsAppChannelRoute route = routeRepository.findByPhoneNumberId(pnid).orElseGet(() -> {
            WhatsAppChannelRoute created = new WhatsAppChannelRoute();
            created.setPhoneNumberId(pnid);
            created.setStatus(WhatsAppChannelRouteStatuses.ACTIVE);
            return created;
        });
        route.setBusinessId(business.getId());
        route.setDisplayNumber(trimToNull(body.displayNumber()));
        route.setLabel(trimToNull(body.label()));
        route.setUpdatedAt(Instant.now());
        return toRow(routeRepository.save(route), business.getName());
    }

    /**
     * Own-credentials payload for {@link #adopt}. Null members leave the route on platform keys.
     */
    public record RouteCredentials(
            boolean ownCredentials,
            String graphVersion,
            String accessTokenEnc,
            String appSecretEnc,
            String verifyTokenEnc
    ) {
    }

    /**
     * Idempotently adopt a shop-owned Meta number as a route. Called when a shop saves the
     * {@code phone_number_id} it already manages (Business → Configuration → WhatsApp) so
     * super-admin never has to re-key it. Never clobbers an existing assignment to a different
     * shop — super-admin owns conflicts.
     *
     * @return the route row when created/updated, {@code null} when blank or owned by another shop
     */
    @Transactional
    public NumberRow adopt(String phoneNumberId, String businessId, String label, RouteCredentials credentials) {
        String pnid = trimToNull(phoneNumberId);
        if (pnid == null || businessId == null || businessId.isBlank()) {
            return null;
        }
        WhatsAppChannelRoute route = routeRepository.findByPhoneNumberId(pnid).orElse(null);
        if (route != null && !route.getBusinessId().equals(businessId)) {
            return null;
        }
        if (route == null) {
            route = new WhatsAppChannelRoute();
            route.setPhoneNumberId(pnid);
            route.setBusinessId(businessId);
            route.setLabel(trimToNull(label) == null ? "From tenant settings" : label.trim());
            route.setStatus(WhatsAppChannelRouteStatuses.ACTIVE);
        }
        if (credentials != null) {
            route.setOwnCredentials(credentials.ownCredentials());
            route.setGraphVersion(trimToNull(credentials.graphVersion()));
            route.setAccessTokenEnc(credentials.accessTokenEnc());
            route.setAppSecretEnc(credentials.appSecretEnc());
            route.setVerifyTokenEnc(credentials.verifyTokenEnc());
        }
        route.setUpdatedAt(Instant.now());
        return toRow(routeRepository.save(route), null);
    }

    @Transactional
    public NumberRow pause(String phoneNumberId) {
        return setStatus(phoneNumberId, WhatsAppChannelRouteStatuses.PAUSED);
    }

    @Transactional
    public NumberRow resume(String phoneNumberId) {
        return setStatus(phoneNumberId, WhatsAppChannelRouteStatuses.ACTIVE);
    }

    private NumberRow setStatus(String phoneNumberId, String status) {
        WhatsAppChannelRoute route = routeRepository.findByPhoneNumberId(requirePhoneNumberId(phoneNumberId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Number is not routed"));
        route.setStatus(status);
        route.setUpdatedAt(Instant.now());
        WhatsAppChannelRoute saved = routeRepository.save(route);
        String name = businessRepository.findByIdAndDeletedAtIsNull(saved.getBusinessId())
                .map(Business::getName)
                .orElse(null);
        return toRow(saved, name);
    }

    private Map<String, String> businessNames(List<WhatsAppChannelRoute> routes) {
        List<String> ids = routes.stream()
                .map(WhatsAppChannelRoute::getBusinessId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        Map<String, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            businessRepository.findAllById(ids).forEach(b -> names.put(b.getId(), b.getName()));
        }
        return names;
    }

    private static NumberRow toRow(WhatsAppChannelRoute route, String businessName) {
        return new NumberRow(
                route.getPhoneNumberId(),
                route.getDisplayNumber(),
                route.getLabel(),
                route.getStatus(),
                route.getQualityRating(),
                route.getBusinessId(),
                businessName,
                route.getCreatedAt(),
                route.getUpdatedAt(),
                route.isOwnCredentials());
    }

    private static String requirePhoneNumberId(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "phoneNumberId is required");
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
