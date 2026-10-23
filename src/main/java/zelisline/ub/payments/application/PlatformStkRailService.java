package zelisline.ub.payments.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PlatformDarajaSettings;
import zelisline.ub.payments.domain.PlatformKioskPaySettings;
import zelisline.ub.payments.domain.PlatformMpesaCustodyProviders;
import zelisline.ub.platform.application.PlatformDomainSettingsService;

/**
 * The platform M-Pesa rail that collects Palmart's own revenue — package renewals
 * today.
 *
 * <p>It resolves the <strong>Custody rail</strong> Super Admin selects under
 * Payments → Custody rail: the collect-and-settle provider tenant till-only shops
 * already pay into (Safaricom Daraja M-Pesa Express, or the legacy platform
 * KopoKopo till). Platform charges collect on that same account — PartyB stays the
 * platform shortcode, so there is no tenant till and no B2B settlement leg.
 *
 * <p>If the Custody rail is Off, the Palmart KopoKopo till under Platform → Domains
 * stays as the fallback so a deployment that never enabled Daraja still renews —
 * the same rail tenant SMS-credit purchases use. A charge is never pinned to one
 * provider in code; Super Admin owns which rail is live.
 */
@Service
@RequiredArgsConstructor
public class PlatformStkRailService {

    private static final Logger log = LoggerFactory.getLogger(PlatformStkRailService.class);

    /** Daraja's {@code AccountReference} is capped at 12 alphanumerics. */
    static final int DARAJA_ACCOUNT_REFERENCE_MAX = 12;

    static final String NOT_CONFIGURED_MESSAGE =
            "Platform M-Pesa is not configured. Ask Super Admin to turn on the Custody rail "
                    + "(Payments → Custody rail → Daraja), or add a Palmart KopoKopo till under "
                    + "Platform → Domains.";

    private final ObjectProvider<PlatformCustodySettlementService> custodySettlement;
    private final ObjectProvider<PlatformDomainSettingsService> domainSettings;

    /**
     * @param gatewayType which gateway implementation runs the STK push
     * @param configId    the synthetic config id stored on {@code gateway_stk_pushes}
     *                    so webhooks and the poller can find the right credentials
     * @param label       human-readable name, for logs and support messages
     * @param credentials decrypted gateway credentials
     */
    public record Rail(
            GatewayType gatewayType,
            String configId,
            String label,
            Map<String, String> credentials
    ) {

        public boolean isDaraja() {
            return gatewayType == GatewayType.DARAJA;
        }

        /**
         * Credentials for one STK request. Daraja gets a short {@code AccountReference}
         * so a long merchant reference cannot be rejected as {@code 400.002.02}; the
         * full reference still travels on the push row and through KopoKopo.
         */
        public Map<String, String> credentialsFor(String reference) {
            if (!isDaraja()) {
                return credentials;
            }
            Map<String, String> out = new LinkedHashMap<>(credentials);
            String accountReference = darajaAccountReference(reference);
            if (accountReference != null) {
                out.put("accountReference", accountReference);
            }
            return out;
        }
    }

    /** The configured platform rail, or empty when Super Admin has set up no rail at all. */
    public Optional<Rail> resolve() {
        PlatformCustodySettlementService custody = custodySettlement.getIfAvailable();
        if (custody != null) {
            String provider = custody.activeProvider();
            boolean providerSelected = PlatformMpesaCustodyProviders.DARAJA.equals(provider)
                    || PlatformMpesaCustodyProviders.KOPOKOPO.equals(provider);
            if (providerSelected) {
                Optional<PlatformCustodySettlementService.CollectRail> collect = custody.resolveCollectRail();
                if (collect.isPresent()) {
                    return Optional.of(fromCustodyRail(collect.get()));
                }
                // Do not hop rails silently: a selected-but-not-ready custody provider would
                // otherwise charge through the very KopoKopo till that keeps failing OAuth.
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "The Custody rail is set to " + provider
                                + " but its platform credentials are not ready. Ask Super Admin to "
                                + "finish Payments → Custody rail setup.");
            }
        }

        PlatformDomainSettingsService domains = domainSettings.getIfAvailable();
        if (domains != null && domains.palmartStkConfigured()) {
            Map<String, String> creds = domains.resolvePalmartStkCredentials();
            if (!creds.isEmpty()) {
                return Optional.of(new Rail(
                        GatewayType.KOPOKOPO,
                        PlatformDomainSettingsService.PLATFORM_DOMAIN_STK_CONFIG_ID,
                        "Palmart KopoKopo till",
                        Map.copyOf(creds)));
            }
        }
        return Optional.empty();
    }

    /** @throws ResponseStatusException 503 when no platform M-Pesa rail is set up. */
    public Rail require(String purpose) {
        Rail rail = resolve().orElseThrow(
                () -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED_MESSAGE));
        log.info("Platform M-Pesa rail for {}: {} ({})", purpose, rail.label(), rail.gatewayType());
        return rail;
    }

    private static Rail fromCustodyRail(PlatformCustodySettlementService.CollectRail collect) {
        boolean daraja = collect.gatewayType() == GatewayType.DARAJA;
        return new Rail(
                collect.gatewayType(),
                daraja
                        ? PlatformDarajaSettings.PLATFORM_DARAJA_CONFIG_ID
                        : PlatformKioskPaySettings.PLATFORM_KOPOKOPO_CONFIG_ID,
                "Custody rail (" + collect.provider() + ")",
                Map.copyOf(collect.credentials()));
    }

    /** 12-char alphanumeric reference, or {@code null} when there is nothing to send. */
    static String darajaAccountReference(String reference) {
        if (reference == null) {
            return null;
        }
        String compact = reference.replaceAll("[^A-Za-z0-9]", "");
        if (compact.isBlank()) {
            return null;
        }
        return compact.length() <= DARAJA_ACCOUNT_REFERENCE_MAX
                ? compact
                : compact.substring(0, DARAJA_ACCOUNT_REFERENCE_MAX);
    }
}
