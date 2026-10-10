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

    /** A configured app secret and where it came from — for verification and support logs. */
    public record CandidateAppSecret(String source, String secret) {
    }

    /** Every app secret that may have signed an inbound webhook: platform + own-credentials routes. */
    @Transactional(readOnly = true)
    public Set<String> candidateAppSecrets() {
        Set<String> secrets = new LinkedHashSet<>();
        for (CandidateAppSecret candidate : candidateAppSecretList()) {
            secrets.add(candidate.secret());
        }
        return secrets;
    }

    /**
     * Fingerprints ({@code source:…last4}) of the configured app secrets. Safe for logs, so a
     * signature mismatch can be compared against the Meta dashboard without leaking a secret.
     */
    @Transactional(readOnly = true)
    public java.util.List<String> candidateAppSecretFingerprints() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (CandidateAppSecret candidate : candidateAppSecretList()) {
            String secret = candidate.secret();
            out.add(candidate.source()
                    + (secret.length() <= 4 ? ":****" : ":…" + secret.substring(secret.length() - 4)));
        }
        return out;
    }

    private java.util.List<CandidateAppSecret> candidateAppSecretList() {
        java.util.List<CandidateAppSecret> out = new java.util.ArrayList<>();
        String platform = platformIntegrationSettingsService.resolveMetaWhatsApp().appSecret();
        if (notBlank(platform)) {
            out.add(new CandidateAppSecret("platform", platform.trim()));
        }
        for (WhatsAppChannelRoute route : routeRepository.findByOwnCredentialsTrue()) {
            String secret = decrypt(route.getAppSecretEnc());
            if (notBlank(secret)) {
                out.add(new CandidateAppSecret("route:" + route.getPhoneNumberId(), secret.trim()));
            }
        }
        return out;
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
