package zelisline.ub.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.PlatformCustodySettlementResponse;
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
import zelisline.ub.payments.domain.spi.ValidationResult;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.KopokopoPaymentGateway;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;
import zelisline.ub.payments.repository.PlatformCustodySettlementRepository;

/**
 * Model B auto-settle for {@link GatewayType#CUSTODY_MPESA}.
 * Collect and settle use the <strong>same</strong> SA custody provider
 * ({@link PlatformMpesaCustodyProviders}) — never Daraja STK + KopoKopo Send Money.
 *
 * <p>The Send Money HTTP call is never made inside the webhook/STK-confirmation
 * transaction: the settlement row is persisted first and the disburse attempt runs
 * in its own transaction after commit (or from the reconciler).
 */
@Service
@RequiredArgsConstructor
public class PlatformCustodySettlementService {

    private static final Logger log = LoggerFactory.getLogger(PlatformCustodySettlementService.class);

    /** PENDING rows older than this are re-attempted by the reconciler. */
    private static final Duration RETRY_AGE = Duration.ofMinutes(2);
    /** SETTLING rows older than this are polled at KopoKopo for their true status. */
    private static final Duration STALE_SETTLING = Duration.ofMinutes(10);

    /** KopoKopo rejection text when the platform till lacks liquid funds. */
    private static final String[] FLOAT_INSUFFICIENT_MARKERS = {
            "exceeds amount available to move",
            "amount available to move",
            "platform payment float",
            "insufficient_funds",
            "insufficient funds",
    };

    private static final long FLOAT_PAUSE_MINUTES = 10L;

    private final PlatformCustodySettlementRepository settlementRepository;
    private final PaymentGatewayConfigRepository configRepository;
    private final KopokopoPaymentGateway kopokopoPaymentGateway;
    private final ObjectProvider<PlatformMpesaCustodySettingsService> custodySettingsService;
    private final ObjectProvider<PlatformKioskPaySettingsService> platformKioskPaySettingsService;
    private final ObjectMapper objectMapper;
    /** Own proxy, so each disburse attempt opens its own transaction. */
    private final ObjectProvider<PlatformCustodySettlementService> self;

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

    /**
     * Persist the settlement for a confirmed custody STK, then disburse after commit.
     * Never performs the outbound call inside this (caller's) transaction.
     */
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

        scheduleDisburse(row.getId());
    }

    /**
     * Settle the KopoKopo Send Money callback for a custody settlement.
     *
     * <p>Matches on the Send Money <em>resource id</em> ({@code gatewayCheckoutId}) first —
     * the {@code gatewayTransactionId} is the M-Pesa transaction reference and differs.
     */
    @Transactional
    public boolean handleSendMoneyWebhook(WebhookResult parsed) {
        if (parsed == null) {
            return false;
        }
        var opt = resolveBySendMoneyId(parsed.gatewayCheckoutId(), parsed.gatewayTransactionId());
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
            applySettled(row);
            return true;
        }
        if (parsed.terminalFailure()) {
            String raw = parsed.failureMessage() != null ? parsed.failureMessage() : "Send Money failed";
            noteProviderFailure(row, raw);
            applyFailed(row, raw);
            return true;
        }
        return true;
    }

    /**
     * Retry disburse for PENDING settlements never accepted by the provider.
     * Not transactional: each attempt opens its own short transaction.
     */
    public void reconcilePending() {
        Instant cutoff = Instant.now().minus(RETRY_AGE);
        List<PlatformCustodySettlement> pending =
                settlementRepository.findByStatusAndCreatedAtBefore(
                        PlatformCustodySettlementStatuses.PENDING, cutoff);
        for (PlatformCustodySettlement row : pending) {
            if (row.getDisbursementId() != null && !row.getDisbursementId().isBlank()) {
                continue;
            }
            try {
                self.getObject().attemptDisburse(row.getId());
            } catch (Exception e) {
                log.warn("Custody disburse retry failed settlement={}: {}", row.getId(), e.getMessage());
            }
        }
    }

    /**
     * Poll SETTLING settlements that never got a webhook, so a missed callback cannot
     * leave a payout stuck forever. The provider call runs outside a transaction; the
     * result is written by {@code applyProviderStatus}.
     */
    public void reconcileInFlight() {
        Instant cutoff = Instant.now().minus(STALE_SETTLING);
        List<PlatformCustodySettlement> settling =
                settlementRepository.findByStatusAndCreatedAtBefore(
                        PlatformCustodySettlementStatuses.SETTLING, cutoff);
        if (settling.isEmpty()) {
            return;
        }
        PlatformKioskPaySettingsService kiosk = platformKioskPaySettingsService.getIfAvailable();
        Map<String, String> creds = kiosk == null ? Map.of() : kiosk.kopokopoCredentials().orElse(Map.of());
        if (creds.isEmpty()) {
            return;
        }
        for (PlatformCustodySettlement row : settling) {
            String sendMoneyId = row.getDisbursementId();
            if (sendMoneyId == null || sendMoneyId.isBlank()) {
                log.error("Custody settlement stuck in SETTLING with no provider id — needs manual reconcile id={}",
                        row.getId());
                continue;
            }
            try {
                WebhookResult status = kopokopoPaymentGateway.querySendMoneyStatus(sendMoneyId, creds);
                if (status == null) {
                    continue;
                }
                if (status.success()) {
                    self.getObject().applyProviderStatus(row.getId(), true, null);
                } else if (status.terminalFailure()) {
                    String raw = status.failureMessage() != null ? status.failureMessage() : "Send Money failed";
                    self.getObject().applyProviderStatus(row.getId(), false, raw);
                }
            } catch (Exception e) {
                log.warn("Custody Send Money status poll failed id={}: {}", row.getId(), e.getMessage());
            }
        }
    }

    /** Reload one settlement and attempt disburse in a fresh transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attemptDisburse(String settlementId) {
        PlatformCustodySettlement row = settlementRepository.findById(settlementId).orElse(null);
        if (row == null) {
            return;
        }
        attemptDisburse(row);
    }

    /** Apply a polled provider status in its own short transaction. */
    @Transactional
    public void applyProviderStatus(String settlementId, boolean success, String failureMessage) {
        PlatformCustodySettlement row = settlementRepository.findById(settlementId).orElse(null);
        if (row == null
                || PlatformCustodySettlementStatuses.SETTLED.equals(row.getStatus())
                || PlatformCustodySettlementStatuses.FAILED.equals(row.getStatus())) {
            return;
        }
        if (success) {
            applySettled(row);
        } else {
            noteProviderFailure(row, failureMessage);
            applyFailed(row, failureMessage);
        }
    }

    // ── Ops ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PlatformCustodySettlementResponse> listForSuperAdmin(int limit) {
        int capped = Math.min(Math.max(limit, 1), 200);
        return settlementRepository
                .findAll(PageRequest.of(0, capped, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream()
                .map(PlatformCustodySettlementService::toResponse)
                .toList();
    }

    /**
     * Ops retry for a terminal {@code FAILED} settlement (provider declined). Only FAILED
     * rows are retryable: a SETTLING row may already have moved money.
     */
    @Transactional
    public PlatformCustodySettlementResponse retry(String settlementId) {
        PlatformCustodySettlement row = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Settlement not found"));
        if (!PlatformCustodySettlementStatuses.FAILED.equals(row.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only FAILED settlements can be retried. Current: " + row.getStatus());
        }
        row.setStatus(PlatformCustodySettlementStatuses.PENDING);
        row.setFailureReason(null);
        row.setDisbursementId(null);
        settlementRepository.save(row);
        scheduleDisburse(row.getId());
        return toResponse(row);
    }

    /** Platform credentials for disburse (null if unavailable). */
    private Map<String, String> platformKopokopoCreds() {
        PlatformKioskPaySettingsService kiosk = platformKioskPaySettingsService.getIfAvailable();
        return kiosk == null ? Map.of() : kiosk.kopokopoCredentials().orElse(Map.of());
    }

    /**
     * Non-destructive health check of the active custody rail for the tenant-facing
     * “Test” action on a till/paybill-only method. Confirms the SA rail is selected and
     * that the platform KopoKopo credentials can authenticate.
     */
    @Transactional(readOnly = true)
    public RailTestResult testActiveRail() {
        String provider = activeProvider();
        if (PlatformMpesaCustodyProviders.OFF.equals(provider)) {
            return new RailTestResult(false, "CUSTODY_OFF",
                    "Platform custody is Off. Ask Super Admin to set Platform custody provider to KopoKopo.");
        }
        if (PlatformMpesaCustodyProviders.DARAJA.equals(provider)) {
            return new RailTestResult(false, "DARAJA_UNAVAILABLE",
                    "Kiosk-powered till/paybill is not available on Daraja yet (disburse pending).");
        }
        Map<String, String> creds = platformKopokopoCreds();
        if (creds.isEmpty()) {
            return new RailTestResult(false, "NO_CREDENTIALS",
                    "Platform KopoKopo credentials are not configured.");
        }
        try {
            PaymentGatewayConfig probe = new PaymentGatewayConfig();
            probe.setGatewayType(GatewayType.KOPOKOPO);
            probe.setCredentialsJson(objectMapper.writeValueAsString(creds));
            ValidationResult result = kopokopoPaymentGateway.validateConfiguration(probe);
            if (result.valid()) {
                return new RailTestResult(true, null,
                        "Kiosk rail reachable — collect and settle on KopoKopo.");
            }
            return new RailTestResult(false,
                    result.errorCode() != null ? result.errorCode() : "AUTH_FAILED",
                    result.errorMessage() != null
                            ? result.errorMessage()
                            : "Platform KopoKopo could not be reached.");
        } catch (Exception e) {
            return new RailTestResult(false, "INTERNAL_ERROR", e.getMessage());
        }
    }

    // ── Internals ───────────────────────────────────────────────────

    private void scheduleDisburse(String settlementId) {
        Runnable attempt = () -> {
            try {
                self.getObject().attemptDisburse(settlementId);
            } catch (Exception e) {
                log.error("Custody disburse attempt failed settlement={}: {}", settlementId, e.getMessage(), e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    attempt.run();
                }
            });
        } else {
            attempt.run();
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
            applyFailed(row, "Unsupported custody provider for disburse: " + row.getProvider());
            return;
        }
        attemptKopokopoSendMoney(row);
    }

    private void attemptKopokopoSendMoney(PlatformCustodySettlement row) {
        Map<String, String> creds = platformKopokopoCreds();
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
            noteProviderFailure(row, msg);
            applyFailed(row, msg);
            return;
        }

        row.setDisbursementId(result.sendMoneyId());
        row.setStatus(PlatformCustodySettlementStatuses.SETTLING);
        row.setFailureReason(null);
        settlementRepository.save(row);
        log.info("Custody Send Money accepted: id={} disbursementId={}", row.getId(), result.sendMoneyId());
    }

    private void applySettled(PlatformCustodySettlement row) {
        if (PlatformCustodySettlementStatuses.SETTLED.equals(row.getStatus())) {
            return;
        }
        row.setStatus(PlatformCustodySettlementStatuses.SETTLED);
        row.setSettledAt(Instant.now());
        row.setFailureReason(null);
        settlementRepository.save(row);
        log.info("Platform custody settled (KK): id={} disbursementId={}",
                row.getId(), row.getDisbursementId());
    }

    private void applyFailed(PlatformCustodySettlement row, String reason) {
        if (PlatformCustodySettlementStatuses.SETTLED.equals(row.getStatus())
                || PlatformCustodySettlementStatuses.FAILED.equals(row.getStatus())) {
            return;
        }
        row.setStatus(PlatformCustodySettlementStatuses.FAILED);
        row.setFailureReason(truncate(reason, 512));
        settlementRepository.save(row);
        log.error("Platform custody settlement FAILED: id={} business={} amount={} {} provider={} reason=\"{}\"",
                row.getId(), row.getBusinessId(), row.getAmount(), row.getCurrency(),
                row.getProvider(), reason);
    }

    /** Pause platform Send Money when the shared till is dry — mirrors Kiosk Pay withdraws. */
    private void noteProviderFailure(PlatformCustodySettlement row, String raw) {
        if (!isFloatInsufficient(raw)) {
            return;
        }
        PlatformKioskPaySettingsService kiosk = platformKioskPaySettingsService.getIfAvailable();
        if (kiosk != null) {
            kiosk.markSendMoneyFloatConstrained(Duration.ofMinutes(FLOAT_PAUSE_MINUTES));
        }
        log.warn("Custody Send Money FLOAT — paused {} min | settlementId={} business={} amount={} provider=\"{}\"",
                FLOAT_PAUSE_MINUTES, row.getId(), row.getBusinessId(), row.getAmount(), raw);
    }

    private static boolean isFloatInsufficient(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        for (String marker : FLOAT_INSUFFICIENT_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
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

    /**
     * The KopoKopo Send Money resource id is carried in {@code gatewayCheckoutId}; the
     * {@code gatewayTransactionId} is the M-Pesa transaction reference. Prefer the
     * resource id, then fall back (and tolerate a stored URL with a trailing path).
     */
    private Optional<PlatformCustodySettlement> resolveBySendMoneyId(String checkoutId, String transactionId) {
        Optional<PlatformCustodySettlement> found = findByDisbursementId(checkoutId);
        if (found.isEmpty()) {
            found = findByDisbursementId(transactionId);
        }
        return found;
    }

    private Optional<PlatformCustodySettlement> findByDisbursementId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return Optional.empty();
        }
        String trimmed = rawId.trim();
        Optional<PlatformCustodySettlement> direct = settlementRepository.findByDisbursementId(trimmed);
        if (direct.isPresent()) {
            return direct;
        }
        int slash = trimmed.lastIndexOf('/');
        if (slash >= 0 && slash < trimmed.length() - 1) {
            return settlementRepository.findByDisbursementId(trimmed.substring(slash + 1));
        }
        return Optional.empty();
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

    private static PlatformCustodySettlementResponse toResponse(PlatformCustodySettlement s) {
        return new PlatformCustodySettlementResponse(
                s.getId(),
                s.getBusinessId(),
                s.getGatewayConfigId(),
                s.getStkPushId(),
                s.getProvider(),
                s.getAmount(),
                s.getCurrency(),
                s.getDestinationType(),
                s.getDestinationTill(),
                s.getDestinationPaybill(),
                s.getDestinationAccount(),
                s.getStatus(),
                s.getDisbursementId(),
                s.getFailureReason(),
                s.getCreatedAt(),
                s.getUpdatedAt(),
                s.getSettledAt());
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

    /** Result of {@link #testActiveRail()}; {@code code} is null on success. */
    public record RailTestResult(boolean ok, String code, String message) {
    }
}
