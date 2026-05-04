package zelisline.ub.integrations.webhook.application;

import java.net.URI;
import java.security.SecureRandom;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.webhook.WebhookEventTypes;
import zelisline.ub.integrations.webhook.api.dto.CreateWebhookSubscriptionRequest;
import zelisline.ub.integrations.webhook.api.dto.CreatedWebhookSubscriptionResponse;
import zelisline.ub.integrations.webhook.api.dto.WebhookSubscriptionResponse;
import zelisline.ub.integrations.webhook.domain.WebhookDelivery;
import zelisline.ub.integrations.webhook.domain.WebhookSubscription;
import zelisline.ub.integrations.webhook.repository.WebhookDeliveryRepository;
import zelisline.ub.integrations.webhook.repository.WebhookSubscriptionRepository;
import zelisline.ub.integrations.webhook.support.WebhookTargetHostValidator;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Service
@RequiredArgsConstructor
public class WebhookSubscriptionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SIGNING_SECRET_PREFIX = "whsec_";

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookTargetHostValidator webhookTargetHostValidator;

    @Transactional(readOnly = true)
    public List<WebhookSubscriptionResponse> list(HttpServletRequest http, TenantPrincipal principal) {
        String businessId = TenantRequestIds.requireMatchingTenant(http, principal.businessId());
        return subscriptionRepository.findByBusinessIdOrderByCreatedAtDesc(businessId).stream()
                .map(WebhookSubscriptionService::toPublicRow)
                .toList();
    }

    @Transactional
    public CreatedWebhookSubscriptionResponse create(
            HttpServletRequest http,
            TenantPrincipal principal,
            CreateWebhookSubscriptionRequest request
    ) {
        String businessId = TenantRequestIds.requireMatchingTenant(http, principal.businessId());
        String url = normalizeUrl(request.targetUrl());
        List<String> events = canonicalizeEvents(request.events());

        String secretPlain = SIGNING_SECRET_PREFIX + randomUrlSafeSecret(40);
        WebhookSubscription sub = new WebhookSubscription();
        sub.setBusinessId(businessId);
        sub.setLabel(request.label().trim());
        sub.setTargetUrl(url);
        sub.setSigningSecret(secretPlain);
        sub.setEvents(events);
        sub.setActive(true);
        subscriptionRepository.save(sub);

        return new CreatedWebhookSubscriptionResponse(
                sub.getId(),
                secretPlain,
                sub.getLabel(),
                sub.getTargetUrl(),
                events,
                sub.getCreatedAt()
        );
    }

    @Transactional
    public void revoke(HttpServletRequest http, TenantPrincipal principal, String subscriptionId) {
        String businessId = TenantRequestIds.requireMatchingTenant(http, principal.businessId());
        WebhookSubscription sub = subscriptionRepository.findByIdAndBusinessId(subscriptionId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook not found"));
        sub.setActive(false);
        subscriptionRepository.save(sub);

        List<WebhookDelivery> pending =
                deliveryRepository.findBySubscriptionIdAndStatusIn(subscriptionId, List.of(WebhookDelivery.STATUS_PENDING));
        for (WebhookDelivery d : pending) {
            d.setStatus(WebhookDelivery.STATUS_DEAD);
            d.setLastError("subscription revoked");
            d.setNextAttemptAt(null);
            d.setLastHttpStatus(null);
            deliveryRepository.save(d);
        }
    }

    @Transactional
    public void replayDelivery(HttpServletRequest http, TenantPrincipal principal, String deliveryId) {
        String businessId = TenantRequestIds.requireMatchingTenant(http, principal.businessId());
        WebhookDelivery delivery = deliveryRepository.findByIdAndBusinessId(deliveryId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found"));
        if (!WebhookDelivery.STATUS_DEAD.equals(delivery.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only dead deliveries may be replayed");
        }
        delivery.setStatus(WebhookDelivery.STATUS_PENDING);
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(null);
        delivery.setSentAt(null);
        delivery.setLastHttpStatus(null);
        delivery.setLastError(null);
        deliveryRepository.save(delivery);
        WebhookSubscription sub = subscriptionRepository.findById(delivery.getSubscriptionId()).orElse(null);
        if (sub != null) {
            sub.setActive(true);
            subscriptionRepository.save(sub);
        }
    }

    private static WebhookSubscriptionResponse toPublicRow(WebhookSubscription s) {
        return new WebhookSubscriptionResponse(
                s.getId(),
                s.getLabel(),
                s.getTargetUrl(),
                s.getEvents() == null ? List.of() : List.copyOf(s.getEvents()),
                s.isActive(),
                s.getFailureCount(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }

    private String normalizeUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid target_url");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target_url must use http(s)");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target_url must include a host");
        }
        webhookTargetHostValidator.validateResolvedHost(uri.getHost());
        return uri.toASCIIString();
    }

    private static List<String> canonicalizeEvents(List<String> input) {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String k : input) {
            if (k == null || k.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "events entries must be non-blank");
            }
            String canon = k.trim().toLowerCase(Locale.ROOT);
            if (!WebhookEventTypes.isKnown(canon)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown webhook event type: " + canon);
            }
            ordered.put(canon, canon);
        }
        return ordered.values().stream().toList();
    }

    private static String randomUrlSafeSecret(int entropyBytes) {
        byte[] b = new byte[entropyBytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
