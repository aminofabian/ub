package zelisline.ub.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayStkPush;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.PlatformCustodySettlement;
import zelisline.ub.payments.domain.PlatformCustodySettlementStatuses;
import zelisline.ub.payments.domain.PlatformMpesaCustodyProviders;
import zelisline.ub.payments.domain.StkPushContextType;
import zelisline.ub.payments.domain.spi.SendMoneyRequest;
import zelisline.ub.payments.domain.spi.SendMoneyResult;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.KopokopoPaymentGateway;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;
import zelisline.ub.payments.repository.PlatformCustodySettlementRepository;

/**
 * Model B auto-settle for {@link GatewayType#CUSTODY_MPESA}.
 * Collect and settle use the <strong>same</strong> SA custody provider
 * ({@link PlatformMpesaCustodyProviders}) — never Daraja STK + KopoKopo Send Money.
 */
@Service
@RequiredArgsConstructor
public class PlatformCustodySettlementService {

    private static final Logger log = LoggerFactory.getLogger(PlatformCustodySettlementService.class);

    private static final Duration RETRY_AGE = Duration.ofMinutes(2);

    private final PlatformCustodySettlementRepository settlementRepository;
    private final PaymentGatewayConfigRepository configRepository;
    private final KopokopoPaymentGateway kopokopoPaymentGateway;
    private final ObjectProvider<PlatformMpesaCustodySettingsService> custodySettingsService;
    private final ObjectProvider<PlatformKioskPaySettingsService> platformKioskPaySettingsService;
    private final ObjectMapper objectMapper;

    @Value("${app.public.api-base-url:http://localhost:5050}")
    private String publicApiBaseUrl;

    @Transactional(readOnly = true)
    public boolean platformRailsReady() {
        PlatformMpesaCustodySettingsService settings = custodySettingsService.getIfAvailable();
        return settings != null && settings.custodyAvailableForTenants();
    }

    @Transactional(readOnly = true)
    public String railsNotReadyMessage() {
        PlatformMpesaCustodySettingsService settings = custodySettingsService.getIfAvailable();
        if (settings == null) {
            return "Kiosk-powered till/paybill is not available.";
        }
        return settings.notAvailableMessage();
    }

    @Transactional(readOnly = true)
    public String activeProvider() {
        PlatformMpesaCustodySettingsService settings = custodySettingsService.getIfAvailable();
        return settings == null
                ? PlatformMpesaCustodyProviders.OFF
                : settings.activeProvider();
    }

    /** Platform credentials for STK collect on the active custody rail (null if unavailable). */
    @Transactional(readOnly = true)
    public Optional<CollectRail> resolveCollectRail() {
        String provider = activeProvider();
        if (PlatformMpesaCustodyProviders.KOPOKOPO.equals(provider)) {
            PlatformKioskPaySettingsService kiosk = platformKioskPaySettingsService.getIfAvailable();
            if (kiosk == null) {
                return Optional.empty();
            }
            return kiosk.kopokopoCredentials()
                    .filter(c -> !c.isEmpty())
                    .map(creds -> new CollectRail(GatewayType.KOPOKOPO, provider, creds));
        }
        if (PlatformMpesaCustodyProviders.DARAJA.equals(provider)) {
            // Gated until Daraja disburse ships — custodyAvailableForTenants() is false.
            return Optional.empty();
        }
        return Optional.empty();
    }

    @Transactional
    public void onStkConfirmed(GatewayStkPush push) {
        if (push == null || push.getConfigId() == null || push.getConfigId().isBlank()) {
            return;
        }
        if (!isRetailCustodyContext(push.getContextType())) {
            return;
        }
        PaymentGatewayConfig cfg = configRepository.findById(push.getConfigId()).orElse(null);
        if (cfg == null
                || cfg.getGatewayType() != GatewayType.CUSTODY_MPESA
                || !push.getBusinessId().equals(cfg.getBusinessId())) {
            return;
        }
        if (settlementRepository.findByStkPushId(push.getId()).isPresent()) {
            return;
        }

        Destination dest = parseDestination(cfg.getDisplayInstructionsJson(), objectMapper);
        if (dest == null) {
            log.error("CUSTODY_MPESA config {} missing till/paybill — cannot settle push={}",
                    cfg.getId(), push.getId());
            return;
        }

        String provider = resolveProviderFromPush(push);
        if (PlatformMpesaCustodyProviders.OFF.equals(provider)
                || PlatformMpesaCustodyProviders.DARAJA.equals(provider)) {
            log.error("Custody settle blocked for push={} provider={} (Daraja disburse not available or Off)",
                    push.getId(), provider);
            return;
        }

        PlatformCustodySettlement row = new PlatformCustodySettlement();
        row.setBusinessId(push.getBusinessId());
        row.setGatewayConfigId(cfg.getId());
        row.setStkPushId(push.getId());
        row.setProvider(provider);
        row.setAmount(push.getAmount().setScale(2, RoundingMode.HALF_UP));
        row.setCurrency("KES");
        row.setDestinationType(dest.type());
        row.setDestinationTill(dest.till());
        row.setDestinationPaybill(dest.paybill());
        row.setDestinationAccount(dest.account());
        row.setStatus(PlatformCustodySettlementStatuses.PENDING);
        settlementRepository.save(row);

        attemptDisburse(row);
    }

    @Transactional
    public boolean handleSendMoneyWebhook(WebhookResult parsed) {
        if (parsed == null) {
            return false;
        }
        String sendMoneyId = parsed.gatewayTransactionId() != null
                ? parsed.gatewayTransactionId()
                : parsed.gatewayCheckoutId();
        if (sendMoneyId == null || sendMoneyId.isBlank()) {
            return false;
        }
        var opt = settlementRepository.findByDisbursementId(sendMoneyId);
        if (opt.isEmpty()) {
            String trimmed = sendMoneyId.trim();
            int slash = trimmed.lastIndexOf('/');
            if (slash >= 0 && slash < trimmed.length() - 1) {
                opt = settlementRepository.findByDisbursementId(trimmed.substring(slash + 1));
            }
        }
        if (opt.isEmpty()) {
            return false;
        }
        PlatformCustodySettlement row = opt.get();
        if (!PlatformMpesaCustodyProviders.KOPOKOPO.equals(row.getProvider())) {
            log.warn("Ignoring KK Send Money webhook for non-KK custody settlement={}", row.getId());
            return false;
        }
        if (PlatformCustodySettlementStatuses.SETTLED.equals(row.getStatus())
                || PlatformCustodySettlementStatuses.FAILED.equals(row.getStatus())) {
            return true;
        }
        if (parsed.success()) {
            row.setStatus(PlatformCustodySettlementStatuses.SETTLED);
            row.setSettledAt(Instant.now());
            row.setFailureReason(null);
            settlementRepository.save(row);
            log.info("Platform custody settled (KK): id={} disbursementId={}",
                    row.getId(), row.getDisbursementId());
            return true;
        }
        if (parsed.terminalFailure()) {
            String raw = parsed.failureMessage() != null ? parsed.failureMessage() : "Send Money failed";
            row.setStatus(PlatformCustodySettlementStatuses.FAILED);
            row.setFailureReason(truncate(raw, 512));
            settlementRepository.save(row);
            log.warn("Platform custody Send Money failed: id={} reason={}", row.getId(), raw);
            return true;
        }
        return true;
    }

    @Transactional
    public void reconcilePending() {
        Instant cutoff = Instant.now().minus(RETRY_AGE);
        List<PlatformCustodySettlement> pending =
                settlementRepository.findByStatusAndCreatedAtBefore(
                        PlatformCustodySettlementStatuses.PENDING, cutoff);
        for (PlatformCustodySettlement row : pending) {
            if (row.getDisbursementId() != null && !row.getDisbursementId().isBlank()) {
                continue;
            }
            attemptDisburse(row);
        }
    }

    private void attemptDisburse(PlatformCustodySettlement row) {
        if (PlatformCustodySettlementStatuses.SETTLED.equals(row.getStatus())
                || PlatformCustodySettlementStatuses.FAILED.equals(row.getStatus())) {
            return;
        }
        if (row.getDisbursementId() != null && !row.getDisbursementId().isBlank()) {
            return;
        }
        if (!PlatformMpesaCustodyProviders.KOPOKOPO.equals(row.getProvider())) {
            row.setFailureReason("Unsupported custody provider for disburse: " + row.getProvider());
            row.setStatus(PlatformCustodySettlementStatuses.FAILED);
            settlementRepository.save(row);
            return;
        }
        attemptKopokopoSendMoney(row);
    }

    private void attemptKopokopoSendMoney(PlatformCustodySettlement row) {
        PlatformKioskPaySettingsService kiosk = platformKioskPaySettingsService.getIfAvailable();
        Map<String, String> creds = kiosk == null
                ? Map.of()
                : kiosk.kopokopoCredentials().orElse(Map.of());
        if (creds.isEmpty()) {
            row.setFailureReason("Platform KopoKopo credentials missing");
            settlementRepository.save(row);
            log.error("Custody settle blocked — no platform KK creds settlement={}", row.getId());
            return;
        }

        String till = firstNonBlank(creds, "tillNumber", "shortcode");
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("custodySettlementId", row.getId());
        metadata.put("businessId", row.getBusinessId());
        metadata.put("stkPushId", row.getStkPushId());
        metadata.put("provider", row.getProvider());
        metadata.put("source", "platform_custody_mpesa");

        SendMoneyRequest request = buildSendMoneyRequest(
                row,
                creds,
                publicApiBaseUrl == null ? "" : publicApiBaseUrl.replaceAll("/+$", ""),
                till,
                metadata);

        row.setStatus(PlatformCustodySettlementStatuses.SETTLING);
        settlementRepository.save(row);

        SendMoneyResult result = kopokopoPaymentGateway.sendMoney(request);
        if (result != null && !result.accepted() && "NETWORK_ERROR".equals(result.responseCode())) {
            row.setFailureReason("KopoKopo unreachable during Send Money — outcome unknown, needs ops reconcile");
            settlementRepository.save(row);
            log.error("Custody Send Money NETWORK_ERROR settlement={} — held in SETTLING", row.getId());
            return;
        }
        if (result == null || !result.accepted() || result.sendMoneyId() == null || result.sendMoneyId().isBlank()) {
            String msg = result != null && result.message() != null
                    ? result.message()
                    : "KopoKopo Send Money declined";
            row.setStatus(PlatformCustodySettlementStatuses.FAILED);
            row.setFailureReason(truncate(msg, 512));
            settlementRepository.save(row);
            log.warn("Custody Send Money declined settlement={}: {}", row.getId(), msg);
            return;
        }

        row.setDisbursementId(result.sendMoneyId());
        row.setStatus(PlatformCustodySettlementStatuses.SETTLING);
        row.setFailureReason(null);
        settlementRepository.save(row);
        log.info("Custody Send Money accepted: id={} disbursementId={}", row.getId(), result.sendMoneyId());
    }

    private String resolveProviderFromPush(GatewayStkPush push) {
        if (push.getGatewayType() == GatewayType.KOPOKOPO) {
            return PlatformMpesaCustodyProviders.KOPOKOPO;
        }
        if (push.getGatewayType() == GatewayType.DARAJA) {
            return PlatformMpesaCustodyProviders.DARAJA;
        }
        return activeProvider();
    }

    private static SendMoneyRequest buildSendMoneyRequest(
            PlatformCustodySettlement row,
            Map<String, String> creds,
            String callbackBase,
            String sourceIdentifier,
            Map<String, String> metadata
    ) {
        BigDecimal amount = row.getAmount();
        String description = "Settle " + row.getId().substring(0, Math.min(8, row.getId().length()));
        if (SendMoneyRequest.DEST_TILL.equals(row.getDestinationType())) {
            return new SendMoneyRequest(
                    creds, callbackBase, SendMoneyRequest.DEST_TILL,
                    null, row.getDestinationTill(), null, null,
                    amount, row.getCurrency(), description, sourceIdentifier, metadata);
        }
        if (SendMoneyRequest.DEST_PAYBILL.equals(row.getDestinationType())) {
            return new SendMoneyRequest(
                    creds, callbackBase, SendMoneyRequest.DEST_PAYBILL,
                    null, null, row.getDestinationPaybill(), row.getDestinationAccount(),
                    amount, row.getCurrency(), description, sourceIdentifier, metadata);
        }
        throw new IllegalStateException("Unsupported custody destination: " + row.getDestinationType());
    }

    static Destination parseDestination(String displayInstructionsJson, ObjectMapper objectMapper) {
        if (displayInstructionsJson == null || displayInstructionsJson.isBlank() || objectMapper == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(displayInstructionsJson);
            String type = text(root, "type");
            if ("till".equalsIgnoreCase(type)) {
                String till = digitsOnly(text(root, "tillNumber"));
                if (till == null || till.length() < 5) {
                    return null;
                }
                return new Destination(SendMoneyRequest.DEST_TILL, till, null, null);
            }
            if ("paybill".equalsIgnoreCase(type)) {
                String paybill = digitsOnly(text(root, "businessNumber"));
                String account = blankToNull(text(root, "accountNumber"));
                if (paybill == null || paybill.length() < 5 || account == null) {
                    return null;
                }
                return new Destination(SendMoneyRequest.DEST_PAYBILL, null, paybill, account);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public String validateDestinationJson(String displayInstructionsJson) {
        Destination d = parseDestination(displayInstructionsJson, objectMapper);
        if (d == null) {
            return "Enter a Buy Goods till, or a Paybill number plus account number.";
        }
        return null;
    }

    @Transactional(readOnly = true)
    public PaymentGatewayConfig findActiveCustodyConfig(String businessId) {
        return configRepository
                .findByBusinessIdAndGatewayTypeAndStatus(
                        businessId, GatewayType.CUSTODY_MPESA, GatewayStatus.ACTIVE)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static boolean isRetailCustodyContext(StkPushContextType type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case WEB_ORDER, POS_PAYMENT, STOREFRONT_CART, GROCERY_INVOICE, CREDIT_AR, WALLET_INTENT -> true;
            default -> false;
        };
    }

    private static String text(JsonNode root, String field) {
        if (root == null || !root.has(field) || root.get(field).isNull()) {
            return null;
        }
        String v = root.get(field).asText();
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String digitsOnly(String s) {
        if (s == null) {
            return null;
        }
        String d = s.replaceAll("\\D", "");
        return d.isBlank() ? null : d;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstNonBlank(Map<String, String> map, String... keys) {
        for (String key : keys) {
            String v = map.get(key);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    record Destination(String type, String till, String paybill, String account) {
    }

    public record CollectRail(GatewayType gatewayType, String provider, Map<String, String> credentials) {
    }
}
