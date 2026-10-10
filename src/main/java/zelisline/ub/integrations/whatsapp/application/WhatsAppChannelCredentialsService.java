package zelisline.ub.integrations.whatsapp.application;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import zelisline.ub.integrations.whatsapp.domain.WhatsAppChannelRoute;
import zelisline.ub.integrations.whatsapp.repository.WhatsAppChannelRouteRepository;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.platform.application.PlatformIntegrationSettingsService;
import zelisline.ub.platform.application.ResolvedMetaWhatsAppConfig;

/**
 * Resolves Meta credentials for one number, supporting <b>per-route</b> (Model B) credentials.
 *
 * <p>A route with {@code own_credentials} rides the shop's own Meta app: outbound uses the route's
 * token + graph version, and inbound webhooks may be signed by the route's app secret. Everything
 * else falls back to the platform keys. See {@code docs/scopes/whatsapp-crm/KEYS.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppChannelCredentialsService {

    private final WhatsAppChannelRouteRepository routeRepository;
    private final PlatformIntegrationSettingsService platformIntegrationSettingsService;
    private final CredentialEncryptionService encryptionService;

    /** Resolved credentials for one outbound send. */
    public record OutboundCredentials(String accessToken, String phoneNumberId, String graphVersion) {
    }

    /**
     * Credentials to send with. When {@code fromPhoneNumberId} names a route with its own
     * credentials, those win; otherwise the platform keys. {@code null} means nothing is configured.
     */
    @Transactional(readOnly = true)
    public OutboundCredentials outboundCredentials(String fromPhoneNumberId) {
        ResolvedMetaWhatsAppConfig platform = platformIntegrationSettingsService.resolveMetaWhatsApp();
        String requested = trimToNull(fromPhoneNumberId);
        if (requested != null) {
            WhatsAppChannelRoute route = routeRepository.findByPhoneNumberId(requested).orElse(null);
            if (route != null && route.isOwnCredentials()) {
                String token = decrypt(route.getAccessTokenEnc());
                if (token != null && !token.isBlank()) {
                    return new OutboundCredentials(
                            token,
                            route.getPhoneNumberId(),
                            firstNonBlank(route.getGraphVersion(), platform.graphVersion(), "v25.0"));
                }
            }
        }
        if (platform.configured()) {
            return new OutboundCredentials(
                    platform.accessToken(),
                    platform.phoneNumberId(),
                    firstNonBlank(platform.graphVersion(), "v25.0"));
        }
        return null;
    }

    /** Every app secret that may have signed an inbound webhook: platform + own-credentials routes. */
    @Transactional(readOnly = true)
    public Set<String> candidateAppSecrets() {
        Set<String> secrets = new LinkedHashSet<>();
        addIfPresent(secrets, platformIntegrationSettingsService.resolveMetaWhatsApp().appSecret());
        for (WhatsAppChannelRoute route : routeRepository.findByOwnCredentialsTrue()) {
            addIfPresent(secrets, decrypt(route.getAppSecretEnc()));
        }
        return secrets;
    }

    /** True when {@code presented} matches the platform verify token or any own-credentials route. */
    @Transactional(readOnly = true)
    public boolean matchesVerifyToken(String presented) {
        if (presented == null || presented.isBlank()) {
            return false;
        }
        if (presented.equals(platformIntegrationSettingsService.resolveMetaWhatsApp().webhookVerifyToken())) {
            return true;
        }
        for (WhatsAppChannelRoute route : routeRepository.findByOwnCredentialsTrue()) {
            if (presented.equals(decrypt(route.getVerifyTokenEnc()))) {
                return true;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public boolean hasAnyVerifyToken() {
        if (notBlank(platformIntegrationSettingsService.resolveMetaWhatsApp().webhookVerifyToken())) {
            return true;
        }
        return routeRepository.findByOwnCredentialsTrue().stream()
                .anyMatch(route -> notBlank(decrypt(route.getVerifyTokenEnc())));
    }

    private String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            return encryptionService.decrypt(encrypted);
        } catch (Exception ex) {
            log.warn("WhatsApp route credential decrypt failed: {}", ex.getMessage());
            return null;
        }
    }

    private static void addIfPresent(Set<String> set, String value) {
        if (notBlank(value)) {
            set.add(value.trim());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (notBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
