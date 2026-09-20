package zelisline.ub.payments.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.PosStkRailResponse;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.PlatformDarajaSettings;
import zelisline.ub.payments.domain.PlatformPaymentGateway;
import zelisline.ub.payments.domain.spi.PaymentGateway;
import zelisline.ub.payments.domain.spi.StkPushRequest;
import zelisline.ub.payments.domain.spi.StkPushResponse;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;

/**
 * Initiates STK Push via the first available ACTIVE online gateway for a business.
 */
@Service
@RequiredArgsConstructor
public class PaymentGatewayStkService {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayStkService.class);

    private static final GatewayType[] STK_GATEWAY_PRIORITY = {
            GatewayType.KOPOKOPO,
            GatewayType.DARAJA,
            GatewayType.PESAPAL,
    };

    private final PaymentGatewayConfigRepository configRepository;
    private final PlatformPaymentGatewayService platformPaymentGatewayService;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final CredentialEncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<PlatformDarajaSettingsService> platformDarajaSettingsService;
    private final ObjectProvider<PlatformCustodySettlementService> custodySettlementService;

    @Value("${app.public.api-base-url:http://localhost:5050}")
    private String publicApiBaseUrl;

    public StkPushOutcome initiate(
            String businessId,
            String phoneNumber,
            BigDecimal amount,
            String reference,
            String description
    ) {
        return initiate(businessId, null, phoneNumber, amount, reference, description);
    }

    public StkPushOutcome initiate(
            String businessId,
            String preferredConfigId,
            String phoneNumber,
            BigDecimal amount,
            String reference,
            String description
    ) {
        if (preferredConfigId == null || preferredConfigId.isBlank()) {
            preferredConfigId = findDefaultActiveStkConfigId(businessId);
        }

        if (preferredConfigId != null && !preferredConfigId.isBlank()) {
            StkPushOutcome preferred = tryConfig(
                    businessId, preferredConfigId, phoneNumber, amount, reference, description);
            if (preferred != null) {
                return preferred;
            }
            // The caller explicitly picked a till/paybill-only (custody) method. If it cannot
            // run, do NOT fall through to the platform Daraja fallback — that has no auto-settle
            // and the shop would never receive the money.
            PaymentGatewayConfig chosen = configRepository.findById(preferredConfigId).orElse(null);
            if (chosen != null && chosen.getGatewayType() == GatewayType.CUSTODY_MPESA) {
                log.warn("Custody config {} not runnable (status={}) — refusing STK for business={}",
                        chosen.getId(), chosen.getStatus(), businessId);
                return StkPushOutcome.rejected(
                        GatewayType.CUSTODY_MPESA.name(), "CUSTODY_UNAVAILABLE", custodyUnavailableMessage());
            }
        }

        StkPushOutcome lastOutcome = null;
        boolean attempted = false;

        for (GatewayType type : STK_GATEWAY_PRIORITY) {
            if (!isPlatformEnabled(type)) {
                continue;
            }
            var configs = configRepository.findByBusinessIdAndGatewayTypeAndStatus(
                    businessId, type, GatewayStatus.ACTIVE);
            for (PaymentGatewayConfig cfg : configs) {
                StkPushOutcome outcome = pushWithConfig(
                        cfg, phoneNumber, amount, reference, description);
                if (outcome == null) {
                    continue;
                }
                attempted = true;
                lastOutcome = outcome;
                if (outcome.accepted()) {
                    return outcome;
                }
            }
        }

        if (attempted && lastOutcome != null) {
            log.warn("STK declined for business={}: {} {}", businessId,
                    lastOutcome.responseCode(), lastOutcome.message());
            return lastOutcome;
        }

        StkPushOutcome custody = tryCustodyMpesa(
                businessId, phoneNumber, amount, reference, description);
        if (custody != null) {
            return custody;
        }

        // A shop with an ACTIVE till/paybill-only method expects Kiosk settlement. Never
        // silently route it to the no-settle platform Daraja fallback.
        PlatformCustodySettlementService custodyService = custodySettlementService.getIfAvailable();
        PaymentGatewayConfig activeCustody = custodyService != null
                ? custodyService.findActiveCustodyConfig(businessId)
                : null;
        if (activeCustody != null) {
            log.warn("Custody config {} active but platform rail not ready — refusing STK for business={}",
                    activeCustody.getId(), businessId);
            return StkPushOutcome.rejected(
                    GatewayType.CUSTODY_MPESA.name(), "CUSTODY_UNAVAILABLE", custodyUnavailableMessage());
        }

        StkPushOutcome platformDaraja = tryPlatformDaraja(
                businessId, phoneNumber, amount, reference, description);
        if (platformDaraja != null) {
            return platformDaraja;
        }

        log.warn("No ACTIVE online STK gateway for business={} (check tenant id, platform enable, Activate)", businessId);
        return StkPushOutcome.rejected(null, "NO_GATEWAY", "Online payment is not available right now.");
    }

    /**
     * Rails the cashier / storefront can offer when more than one STK path is ACTIVE.
     */
    public List<PosStkRailResponse> listActiveStkRails(String businessId) {
        List<PosStkRailResponse> rails = new ArrayList<>();
        List<PlatformPaymentGateway> platformEnabled = platformPaymentGatewayService.listEnabled();
        String defaultId = findDefaultActiveStkConfigId(businessId);

        for (GatewayType type : STK_GATEWAY_PRIORITY) {
            if (!isPlatformEnabled(type)) {
                continue;
            }
            String displayName = platformEnabled.stream()
                    .filter(pg -> pg.getGatewayType() == type)
                    .map(PlatformPaymentGateway::getDisplayName)
                    .findFirst()
                    .orElse(type.name());
            for (PaymentGatewayConfig cfg : configRepository.findByBusinessIdAndGatewayTypeAndStatus(
                    businessId, type, GatewayStatus.ACTIVE)) {
                String label = cfg.getLabel() != null && !cfg.getLabel().isBlank()
                        ? cfg.getLabel()
                        : displayName;
                rails.add(new PosStkRailResponse(
                        cfg.getId(),
                        type.name(),
                        label,
                        displayName,
                        cfg.getId().equals(defaultId) || cfg.isDefault()));
            }
        }

        PlatformCustodySettlementService custody = custodySettlementService.getIfAvailable();
        if (custody != null && custody.platformRailsReady()) {
            for (PaymentGatewayConfig cfg : configRepository.findByBusinessIdAndGatewayTypeAndStatus(
                    businessId, GatewayType.CUSTODY_MPESA, GatewayStatus.ACTIVE)) {
                String label = cfg.getLabel() != null && !cfg.getLabel().isBlank()
                        ? cfg.getLabel()
                        : "Till / paybill";
                rails.add(new PosStkRailResponse(
                        cfg.getId(),
                        GatewayType.CUSTODY_MPESA.name(),
                        label,
                        "Lipa Na M-Pesa",
                        cfg.getId().equals(defaultId) || cfg.isDefault()));
            }
        }

        if (rails.stream().noneMatch(PosStkRailResponse::isDefault) && !rails.isEmpty()) {
            PosStkRailResponse first = rails.getFirst();
            rails.set(0, new PosStkRailResponse(
                    first.configId(),
                    first.gatewayType(),
                    first.label(),
                    first.displayName(),
                    true));
        }
        return rails;
    }

    private StkPushOutcome tryCustodyMpesa(
            String businessId,
            String phoneNumber,
            BigDecimal amount,
            String reference,
            String description
    ) {
        PlatformCustodySettlementService custody = custodySettlementService.getIfAvailable();
        if (custody == null || !custody.platformRailsReady()) {
            return null;
        }
        PaymentGatewayConfig cfg = custody.findActiveCustodyConfig(businessId);
        if (cfg == null) {
            return null;
        }
        return pushCustodyConfig(cfg, phoneNumber, amount, reference, description);
    }

    private StkPushOutcome pushCustodyConfig(
            PaymentGatewayConfig cfg,
            String phoneNumber,
            BigDecimal amount,
            String reference,
            String description
    ) {
        PlatformCustodySettlementService custody = custodySettlementService.getIfAvailable();
        if (custody == null || !custody.platformRailsReady()) {
            return null;
        }
        if (cfg.getStatus() != GatewayStatus.ACTIVE
                || cfg.getGatewayType() != GatewayType.CUSTODY_MPESA) {
            return null;
        }
        var rail = custody.resolveCollectRail().orElse(null);
        if (rail == null || rail.credentials() == null || rail.credentials().isEmpty()) {
            return null;
        }
        Map<String, String> creds = new LinkedHashMap<>(rail.credentials());
        // Same shape as working multi-tenant Express integrations:
        //   PartyB           = platform collection_account (Go Live shortcode)
        //   AccountReference = tenant destination (till / paybill account)
        // Money credits the Merchant (Partner) on Go Live — Express never PartyB-routes
        // to NCBA/foreign paybills. No B2B in this request.
        if (rail.gatewayType() == GatewayType.DARAJA) {
            StkPushOutcome destError = applyFriendStyleExpressDestination(creds, cfg);
            if (destError != null) {
                return destError;
            }
        }
        log.info("STK via platform Daraja Express (CUSTODY_MPESA) business={} config={} partyB={} accountRef={}",
                cfg.getBusinessId(), cfg.getId(),
                firstNonBlank(creds.get("shortcode"), creds.get("tillNumber")),
                creds.get("accountReference"));
        return initiateWithCredentials(
                rail.gatewayType().name(),
                cfg.getId(),
                cfg.getBusinessId(),
                creds,
                phoneNumber,
                amount,
                reference,
                description);
    }

    /**
     * Friend-style Express: PartyB stays the platform collection shortcode;
     * tenant till/paybill goes in AccountReference (max 12) for the USSD prompt / recon.
     */
    private StkPushOutcome applyFriendStyleExpressDestination(
            Map<String, String> creds, PaymentGatewayConfig cfg) {
        PlatformCustodySettlementService.Destination dest =
                PlatformCustodySettlementService.parseDestination(
                        cfg.getDisplayInstructionsJson(), objectMapper);
        if (dest == null) {
            return StkPushOutcome.rejected(
                    GatewayType.CUSTODY_MPESA.name(),
                    "NO_DESTINATION",
                    "Add a till or paybill in Payments settings.");
        }

        // Do not set partyB — DarajaPaymentGateway defaults PartyB = BusinessShortCode.
        creds.remove("partyB");
        creds.remove("PartyB");
        creds.remove("receivingShortcode");

        String accountRef;
        if (dest.till() != null && !dest.till().isBlank()) {
            accountRef = digitsOnly(dest.till());
        } else if (dest.paybill() != null && !dest.paybill().isBlank()) {
            // Prefer the shop account number (e.g. NCBA 5552830017); fall back to paybill digits.
            String account = dest.account() != null ? dest.account().trim() : "";
            accountRef = !account.isBlank() ? account.replaceAll("\\s+", "") : digitsOnly(dest.paybill());
        } else {
            return StkPushOutcome.rejected(
                    GatewayType.CUSTODY_MPESA.name(),
                    "NO_DESTINATION",
                    "Add a till or paybill in Payments settings.");
        }
        if (accountRef == null || accountRef.isBlank()) {
            return StkPushOutcome.rejected(
                    GatewayType.CUSTODY_MPESA.name(),
                    "NO_DESTINATION",
                    "Add a till or paybill in Payments settings.");
        }
        // Express caps AccountReference at 12 characters.
        if (accountRef.length() > 12) {
            accountRef = accountRef.substring(0, 12);
        }
        creds.put("accountReference", accountRef);
        return null;
    }

    private static String digitsOnly(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.isBlank() ? null : digits;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private StkPushOutcome tryPlatformDaraja(
            String businessId,
            String phoneNumber,
            BigDecimal amount,
            String reference,
            String description
    ) {
        PlatformDarajaSettingsService daraja = platformDarajaSettingsService.getIfAvailable();
        if (daraja == null || !daraja.isEnabledAndConfigured()) {
            return null;
        }
        var creds = daraja.credentials().orElse(null);
        if (creds == null || creds.isEmpty()) {
            return null;
        }
        log.info("STK via platform Daraja for business={} partyB={}", businessId, creds.get("shortcode"));
        return initiateWithCredentials(
                GatewayType.DARAJA.name(),
                PlatformDarajaSettings.PLATFORM_DARAJA_CONFIG_ID,
                businessId,
                creds,
                phoneNumber,
                amount,
                reference,
                description);
    }

    /**
     * First ACTIVE tenant config for an enabled platform STK gateway, else custody if ready.
     */
    public String findDefaultActiveStkConfigId(String businessId) {
        for (GatewayType type : STK_GATEWAY_PRIORITY) {
            if (!isPlatformEnabled(type)) {
                continue;
            }
            var configs = configRepository.findByBusinessIdAndGatewayTypeAndStatus(
                    businessId, type, GatewayStatus.ACTIVE);
            for (PaymentGatewayConfig cfg : configs) {
                if (cfg.isDefault()) {
                    return cfg.getId();
                }
            }
            if (!configs.isEmpty()) {
                return configs.getFirst().getId();
            }
        }
        PlatformCustodySettlementService custody = custodySettlementService.getIfAvailable();
        if (custody != null && custody.platformRailsReady()) {
            PaymentGatewayConfig cfg = custody.findActiveCustodyConfig(businessId);
            if (cfg != null) {
                return cfg.getId();
            }
        }
        return null;
    }

    private boolean isPlatformEnabled(GatewayType type) {
        return platformPaymentGatewayService.listEnabled().stream()
                .map(PlatformPaymentGateway::getGatewayType)
                .anyMatch(t -> t == type);
    }

    private String custodyUnavailableMessage() {
        PlatformCustodySettlementService custody = custodySettlementService.getIfAvailable();
        return custody != null
                ? custody.railsNotReadyMessage()
                : "Kiosk-powered till/paybill is not available right now.";
    }

    private StkPushOutcome tryConfig(
            String businessId,
            String configId,
            String phoneNumber,
            BigDecimal amount,
            String reference,
            String description
    ) {
        PaymentGatewayConfig cfg = configRepository.findById(configId).orElse(null);
        if (cfg == null || !businessId.equals(cfg.getBusinessId())) {
            return null;
        }
        if (cfg.getStatus() != GatewayStatus.ACTIVE || cfg.getGatewayType() == GatewayType.MANUAL) {
            return null;
        }
        if (cfg.getGatewayType() == GatewayType.CUSTODY_MPESA) {
            return pushCustodyConfig(cfg, phoneNumber, amount, reference, description);
        }
        return pushWithConfig(cfg, phoneNumber, amount, reference, description);
    }

    private StkPushOutcome pushWithConfig(
            PaymentGatewayConfig cfg,
            String phoneNumber,
            BigDecimal amount,
            String reference,
            String description
    ) {
        GatewayType type = cfg.getGatewayType();
        if (!gatewayRegistry.has(type.name())) {
            return null;
        }
        try {
            String decrypted;
            try {
                decrypted = encryptionService.decrypt(cfg.getCredentialsJson());
            } catch (RuntimeException decryptError) {
                String hint = encryptionService.usesEphemeralKey()
                        ? "Server payment encryption key is not configured — contact the store admin."
                        : "Ask the store to re-save KopoKopo credentials in Payments settings (Test connection, then Activate).";
                return StkPushOutcome.rejected(
                        type.name(),
                        "CREDENTIALS",
                        decryptError.getMessage() != null ? decryptError.getMessage() + " " + hint : hint);
            }
            @SuppressWarnings("unchecked")
            Map<String, String> creds = objectMapper.readValue(decrypted, Map.class);
            return pushWithCredentials(
                    type.name(),
                    cfg.getId(),
                    cfg.getBusinessId(),
                    creds,
                    phoneNumber,
                    amount,
                    reference,
                    description);
        } catch (Exception e) {
            log.error("STK Push via {} failed for config={}", type, cfg.getId(), e);
            return StkPushOutcome.rejected(type.name(), "ERROR", e.getMessage() != null ? e.getMessage() : "Payment request failed");
        }
    }

    public StkPushOutcome initiateWithCredentials(
            String gatewayType,
            String configId,
            String businessIdForRequest,
            Map<String, String> credentials,
            String phoneNumber,
            BigDecimal amount,
            String reference,
            String description
    ) {
        if (credentials == null || credentials.isEmpty()) {
            return StkPushOutcome.rejected(gatewayType, "NO_GATEWAY", "Platform STK credentials are not configured.");
        }
        if (!gatewayRegistry.has(gatewayType)) {
            return StkPushOutcome.rejected(gatewayType, "NO_GATEWAY", "Gateway is not available.");
        }
        try {
            return pushWithCredentials(
                    gatewayType,
                    configId,
                    businessIdForRequest,
                    credentials,
                    phoneNumber,
                    amount,
                    reference,
                    description);
        } catch (Exception e) {
            log.error("STK Push via {} (explicit creds) failed", gatewayType, e);
            return StkPushOutcome.rejected(
                    gatewayType, "ERROR", e.getMessage() != null ? e.getMessage() : "Payment request failed");
        }
    }

    private StkPushOutcome pushWithCredentials(
            String gatewayType,
            String configId,
            String businessIdForRequest,
            Map<String, String> creds,
            String phoneNumber,
            BigDecimal amount,
            String reference,
            String description
    ) {
        PaymentGateway gw = gatewayRegistry.get(gatewayType);
        StkPushRequest pushReq = new StkPushRequest(
                businessIdForRequest,
                phoneNumber,
                amount,
                reference,
                description != null ? description : "Order payment",
                publicApiBaseUrl.replaceAll("/$", ""),
                creds
        );

        StkPushResponse response = gw.initiateStkPush(pushReq);
        if (response.accepted() && response.gatewayCheckoutRequestId() != null) {
            log.info("STK Push via {} accepted: checkoutId={}", gatewayType, response.gatewayCheckoutRequestId());
            return StkPushOutcome.accepted(gatewayType, configId, response.gatewayCheckoutRequestId(),
                    "Check your phone to complete M-Pesa payment.");
        }
        log.warn("STK Push via {} rejected: {} {}", gatewayType, response.responseCode(), response.responseDescription());
        return StkPushOutcome.rejected(gatewayType, response.responseCode(),
                response.responseDescription() != null ? response.responseDescription() : "Payment request declined");
    }

    public record StkPushOutcome(
            boolean accepted,
            String gatewayType,
            String configId,
            String checkoutRequestId,
            String responseCode,
            String message
    ) {
        public static StkPushOutcome accepted(
                String gatewayType, String configId, String checkoutRequestId, String message) {
            return new StkPushOutcome(true, gatewayType, configId, checkoutRequestId, "0", message);
        }

        public static StkPushOutcome rejected(String gatewayType, String code, String message) {
            return new StkPushOutcome(false, gatewayType, null, null, code, message);
        }
    }
}
