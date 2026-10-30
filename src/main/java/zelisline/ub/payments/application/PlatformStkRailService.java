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
 * and SMS-credit top-ups.
 *
 * <p>Safaricom Daraja (M-Pesa Express) wins whenever the platform Daraja app is
 * enabled under Payments → Safaricom Daraja. That is the same rail tenant payments
 * fall back to, and the rail the Custody toggle selects for till-only shops, so a
 * package renewal prompts a normal M-Pesa STK against the platform shortcode
 * (PartyB = shortcode) — no tenant till, no B2B settlement leg.
 *
 * <p>Only when Daraja is off does it drop to the legacy custody KopoKopo rail, then
 * to the Palmart KopoKopo till under Platform → Domains. Both SMS-credit purchases
 * and package renewals use this rail, so neither can fall through to a
 * mis-scoped KopoKopo client. A Custody rail explicitly set to Daraja never falls
 * through to KopoKopo — that fallthrough is what produced {@code invalid_client}
 * OAuth errors in production.
 */
@Service
@RequiredArgsConstructor
public class PlatformStkRailService {

    private static final Logger log = LoggerFactory.getLogger(PlatformStkRailService.class);

    /** Daraja's {@code AccountReference} is capped at 12 alphanumerics. */
    static final int DARAJA_ACCOUNT_REFERENCE_MAX = 12;

    static final String NOT_CONFIGURED_MESSAGE =
            "Platform M-Pesa is not configured. Ask Super Admin to enable Safaricom Daraja "
                    + "(Payments → Safaricom Daraja) or set the Custody rail to Daraja, or add a "
                    + "Palmart KopoKopo till under Platform → Domains.";

    private final ObjectProvider<PlatformDarajaSettingsService> darajaSettings;
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
            // Paybill AccountReference is the destination account, not the purchase id.
            if ("CustomerPayBillOnline".equals(credentials.get("transactionType"))
                    && credentials.get("accountReference") != null
                    && !credentials.get("accountReference").isBlank()) {
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
        String custodyProvider = custody != null
                ? custody.activeProvider()
                : PlatformMpesaCustodyProviders.OFF;

        Map<String, String> darajaCreds = darajaCredentials();

        if (PlatformMpesaCustodyProviders.DARAJA.equals(custodyProvider)) {
            if (darajaCreds.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "The Custody rail is set to Daraja but Safaricom Daraja is not ready. Ask "
                                + "Super Admin to finish Payments → Safaricom Daraja.");
            }
            return Optional.of(darajaRail(darajaCreds));
        }

        // Daraja is the platform's own M-Pesa account: use it for platform revenue even
        // when the Custody toggle is Off, instead of falling back to a KopoKopo till.
        if (!darajaCreds.isEmpty()) {
            return Optional.of(darajaRail(darajaCreds));
        }

        if (PlatformMpesaCustodyProviders.KOPOKOPO.equals(custodyProvider)) {
            Optional<PlatformCustodySettlementService.CollectRail> collect = custody.resolveCollectRail()
                    .filter(rail -> rail.gatewayType() == GatewayType.KOPOKOPO);
            if (collect.isPresent()) {
                return Optional.of(fromCustodyRail(collect.get()));
            }
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "The Custody rail is set to KopoKopo but its platform credentials are not ready. "
                            + "Ask Super Admin to finish Payments → Custody rail setup.");
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

    private Map<String, String> darajaCredentials() {
        PlatformDarajaSettingsService daraja = darajaSettings.getIfAvailable();
        if (daraja == null || !daraja.isEnabledAndConfigured()) {
            return Map.of();
        }
        return daraja.credentials().orElse(Map.of());
    }

    private static Rail darajaRail(Map<String, String> credentials) {
        return new Rail(
                GatewayType.DARAJA,
                PlatformDarajaSettings.PLATFORM_DARAJA_CONFIG_ID,
                "Safaricom Daraja (M-Pesa Express)",
                Map.copyOf(credentials));
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
