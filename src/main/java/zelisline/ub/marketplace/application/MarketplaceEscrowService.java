package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.CreateMarketplaceEscrowHoldRequest;
import zelisline.ub.marketplace.api.dto.MarketplaceEscrowHoldResponse;
import zelisline.ub.marketplace.domain.MarketplaceEscrowHold;
import zelisline.ub.marketplace.domain.MarketplaceEscrowHoldStatuses;
import zelisline.ub.marketplace.domain.MarketplaceEscrowReleaseTriggers;
import zelisline.ub.marketplace.repository.MarketplaceEscrowHoldRepository;
import zelisline.ub.payments.application.KioskPayWalletService;
import zelisline.ub.payments.application.PlatformKioskPaySettingsService;
import zelisline.ub.payments.domain.KioskPayAccount;
import zelisline.ub.payments.domain.PlatformKioskPaySettings;
import zelisline.ub.payments.domain.spi.SendMoneyRequest;
import zelisline.ub.payments.domain.spi.SendMoneyResult;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.KopokopoPaymentGateway;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.application.SupplierDisbursementService;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierPayoutTypes;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * Phase 3 marketplace escrow: earmark Kiosk Pay funds for a supplier, then
 * settle via <strong>platform</strong> KopoKopo Send Money (not tenant BYO AP).
 * Wallet settle waits for Send Money webhook / reconcile (same pattern as Kiosk Pay withdraw).
 */
@Service
@RequiredArgsConstructor
public class MarketplaceEscrowService {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceEscrowService.class);
    private static final Duration STALE_SETTLING = Duration.ofHours(2);

    private final MarketplaceEscrowHoldRepository holdRepository;
    private final SupplierRepository supplierRepository;
    private final KioskPayWalletService walletService;
    private final PlatformKioskPaySettingsService platformKioskPaySettings;
    private final KopokopoPaymentGateway kopokopoPaymentGateway;

    @Value("${app.public.api-base-url:http://localhost:5050}")
    private String publicApiBaseUrl;

    @Transactional(readOnly = true)
    public List<MarketplaceEscrowHoldResponse> listForBusiness(String businessId, int limit) {
        int capped = Math.min(Math.max(limit, 1), 50);
        return holdRepository.findByBusinessIdOrderByCreatedAtDesc(businessId, PageRequest.of(0, capped))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MarketplaceEscrowHoldResponse> listForSuperAdmin(int limit) {
        int capped = Math.min(Math.max(limit, 1), 100);
        return holdRepository.findAll(PageRequest.of(0, capped, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public MarketplaceEscrowHoldResponse get(String businessId, String holdId) {
        return toResponse(requireHold(businessId, holdId));
    }

    @Transactional
    public MarketplaceEscrowHoldResponse createHold(String businessId, CreateMarketplaceEscrowHoldRequest body) {
        if (body == null || body.amount() == null || body.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be positive");
        }
        BigDecimal amount = body.amount().setScale(2, RoundingMode.HALF_UP);
        String idem = body.idempotencyKey() != null && !body.idempotencyKey().isBlank()
                ? body.idempotencyKey().trim()
                : null;
        if (idem != null) {
            var existing = holdRepository.findByFundedLedgerReference("escrow:" + idem);
            if (existing.isPresent()) {
                return toResponse(existing.get());
            }
        }

        Supplier supplier = supplierRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(body.supplierId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));

        if (!SupplierDisbursementService.hasAutomatedPayoutDestination(supplier)) {
            String hint = SupplierPayoutTypes.MOBILE_WALLET.equals(supplier.getPayoutType())
                    && supplier.getPayoutPhone() != null
                    && supplier.getPayoutPhoneVerifiedAt() == null
                    ? "Verify the supplier M-Pesa payout phone before funding escrow"
                    : "Supplier needs an automated payout destination (M-Pesa phone, till, or paybill)";
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, hint);
        }

        String poId = blankToNull(body.purchaseOrderId());
        String invoiceId = blankToNull(body.supplierInvoiceId());
        if (poId == null && invoiceId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "purchaseOrderId or supplierInvoiceId is required");
        }
        if (poId != null) {
            holdRepository.findByBusinessIdAndPurchaseOrderIdAndStatus(
                            businessId, poId, MarketplaceEscrowHoldStatuses.HELD)
                    .ifPresent(h -> {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "An open escrow hold already exists for this purchase order");
                    });
        }

        platformKioskPaySettings.requireEnabledSettings();
        KioskPayAccount account = walletService.getOrCreateForUpdate(businessId);
        if (!account.isActive()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Activate Kiosk Pay and top up before funding escrow");
        }

        MarketplaceEscrowHold hold = new MarketplaceEscrowHold();
        hold.setBusinessId(businessId);
        hold.setSupplierId(supplier.getId());
        hold.setMarketplaceSupplierId(supplier.getMarketplaceSupplierId());
        hold.setPurchaseOrderId(poId);
        hold.setSupplierInvoiceId(invoiceId);
        hold.setKioskPayAccountId(account.getId());
        hold.setAmount(amount);
        hold.setCurrency(body.currency() != null && !body.currency().isBlank()
                ? body.currency().trim().toUpperCase()
                : "KES");
        hold.setStatus(MarketplaceEscrowHoldStatuses.HELD);
        hold.setNote(blankToNull(body.note()));
        hold = holdRepository.save(hold);

        String ref = idem != null ? "escrow:" + idem : "escrow-hold:" + hold.getId();
        hold.setFundedLedgerReference(ref);
        walletService.holdForEscrow(account, amount, hold.getCurrency(), hold.getId(), ref);
        holdRepository.save(hold);

        log.info("Marketplace escrow held: id={} business={} supplier={} amount={}",
                hold.getId(), businessId, supplier.getId(), amount);
        return toResponse(hold);
    }

    @Transactional
    public MarketplaceEscrowHoldResponse cancel(String businessId, String holdId) {
        MarketplaceEscrowHold hold = requireHold(businessId, holdId);
        if (!MarketplaceEscrowHoldStatuses.HELD.equals(hold.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only HELD escrow can be cancelled");
        }
        KioskPayAccount account = walletService.getOrCreateForUpdate(businessId);
        walletService.releaseEscrowHold(
                account,
                hold.getAmount(),
                hold.getCurrency(),
                hold.getId(),
                "escrow-cancel:" + hold.getId());
        hold.setStatus(MarketplaceEscrowHoldStatuses.CANCELLED);
        hold.setReleasedAt(Instant.now());
        holdRepository.save(hold);
        return toResponse(hold);
    }

    /**
     * Queue + attempt Send Money when a linked PO is marked delivered.
     */
    @Transactional
    public void onPurchaseOrderDelivered(String businessId, String purchaseOrderId) {
        if (businessId == null || purchaseOrderId == null || purchaseOrderId.isBlank()) {
            return;
        }
        List<MarketplaceEscrowHold> holds = holdRepository.findByPurchaseOrderIdAndStatus(
                purchaseOrderId, MarketplaceEscrowHoldStatuses.HELD);
        for (MarketplaceEscrowHold hold : holds) {
            if (!businessId.equals(hold.getBusinessId())) {
                continue;
            }
            try {
                queueAndSettle(hold, MarketplaceEscrowReleaseTriggers.PO_DELIVERED);
            } catch (Exception e) {
                log.warn("Escrow auto-release failed hold={} po={}: {}",
                        hold.getId(), purchaseOrderId, e.getMessage());
            }
        }
    }

    /**
     * Supplier portal / Path A delivery update — only acts when status is delivered.
     */
    @Transactional
    public void onDeliveryStatusChanged(String businessId, String purchaseOrderId, String deliveryStatus) {
        if (PurchasingConstants.DELIVERY_DELIVERED.equals(deliveryStatus)) {
            onPurchaseOrderDelivered(businessId, purchaseOrderId);
        }
    }

    @Transactional
    public MarketplaceEscrowHoldResponse releaseManual(
            String businessId,
            String holdId,
            String trigger,
            boolean superAdmin
    ) {
        MarketplaceEscrowHold hold = superAdmin
                ? holdRepository.findById(holdId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Escrow hold not found"))
                : requireHold(businessId, holdId);
        if (businessId != null && !superAdmin && !businessId.equals(hold.getBusinessId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Escrow hold not found");
        }
        if (!MarketplaceEscrowHoldStatuses.HELD.equals(hold.getStatus())
                && !MarketplaceEscrowHoldStatuses.FAILED.equals(hold.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Escrow is not releasable in status " + hold.getStatus());
        }
        // FAILED with funds released to shop cannot be retried from pending.
        if (MarketplaceEscrowHoldStatuses.FAILED.equals(hold.getStatus())
                && hold.getKopokopoSendMoneyId() != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "This escrow already failed after Send Money — create a new hold");
        }
        String t = trigger != null ? trigger : (superAdmin
                ? MarketplaceEscrowReleaseTriggers.MANUAL_SA
                : MarketplaceEscrowReleaseTriggers.MANUAL_TENANT);
        queueAndSettle(hold, t);
        return toResponse(holdRepository.findById(hold.getId()).orElse(hold));
    }

    /**
     * Platform KopoKopo Send Money webhook for escrow settlements.
     *
     * @return true if this webhook matched an escrow hold
     */
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
        var opt = holdRepository.findByKopokopoSendMoneyId(sendMoneyId);
        if (opt.isEmpty()) {
            String trimmed = sendMoneyId.trim();
            int slash = trimmed.lastIndexOf('/');
            if (slash >= 0 && slash < trimmed.length() - 1) {
                opt = holdRepository.findByKopokopoSendMoneyId(trimmed.substring(slash + 1));
            }
        }
        if (opt.isEmpty()) {
            return false;
        }
        MarketplaceEscrowHold hold = opt.get();
        if (MarketplaceEscrowHoldStatuses.SETTLED.equals(hold.getStatus())
                || MarketplaceEscrowHoldStatuses.RELEASED_TO_SHOP.equals(hold.getStatus())
                || MarketplaceEscrowHoldStatuses.CANCELLED.equals(hold.getStatus())) {
            return true;
        }

        if (parsed.success()) {
            finalizeSettled(hold);
            return true;
        }
        if (parsed.terminalFailure()) {
            String raw = parsed.failureMessage() != null ? parsed.failureMessage() : "Send Money failed";
            finalizeSendMoneyFailed(hold, raw);
            return true;
        }
        return true;
    }

    /**
     * Poll SETTLING holds so a missed webhook cannot leave funds stuck in pending.
     */
    @Transactional
    public int reconcileAllInFlight() {
        int changed = 0;
        Instant cutoff = Instant.now().minus(STALE_SETTLING);
        Map<String, String> creds = platformKioskPaySettings.kopokopoCredentials().orElse(Map.of());
        List<MarketplaceEscrowHold> settling = holdRepository.findByStatusOrderByCreatedAtAsc(
                MarketplaceEscrowHoldStatuses.SETTLING, PageRequest.of(0, 100));
        for (MarketplaceEscrowHold hold : settling) {
            String sendMoneyId = hold.getKopokopoSendMoneyId();
            if (sendMoneyId != null && !sendMoneyId.isBlank() && !creds.isEmpty()) {
                try {
                    WebhookResult status = kopokopoPaymentGateway.querySendMoneyStatus(sendMoneyId, creds);
                    if (status != null && status.success()) {
                        finalizeSettled(hold);
                        changed++;
                        continue;
                    }
                    if (status != null && status.terminalFailure()) {
                        String raw = status.failureMessage() != null
                                ? status.failureMessage()
                                : "Send Money failed";
                        finalizeSendMoneyFailed(hold, raw);
                        changed++;
                        continue;
                    }
                } catch (Exception e) {
                    log.debug("Escrow reconcile query failed hold={}: {}", hold.getId(), e.getMessage());
                }
            }
            Instant started = hold.getReleasedAt() != null ? hold.getReleasedAt() : hold.getCreatedAt();
            if (started != null && started.isBefore(cutoff)) {
                if (sendMoneyId == null || sendMoneyId.isBlank()) {
                    // No send money id → initiation outcome unknown (e.g. network error at
                    // the gateway). The transfer may have proceeded; auto-refunding would
                    // double-pay. Leave for ops reconciliation.
                    log.error("Escrow hold stuck SETTLING without sendMoneyId hold={} — needs manual reconcile",
                            hold.getId());
                    continue;
                }
                finalizeSendMoneyFailed(hold, "Escrow Send Money timed out — funds returned to shop");
                changed++;
            }
        }

        List<MarketplaceEscrowHold> queued = holdRepository.findByStatusOrderByCreatedAtAsc(
                MarketplaceEscrowHoldStatuses.RELEASE_QUEUED, PageRequest.of(0, 50));
        for (MarketplaceEscrowHold hold : queued) {
            Instant started = hold.getReleasedAt() != null ? hold.getReleasedAt() : hold.getCreatedAt();
            if (started != null && started.isBefore(cutoff)) {
                hold.setStatus(MarketplaceEscrowHoldStatuses.FAILED);
                hold.setFailureReason("Escrow release stuck in queue — retry manually");
                holdRepository.save(hold);
                changed++;
                continue;
            }
            try {
                settleNow(hold);
                changed++;
            } catch (Exception e) {
                log.warn("Escrow queued settle retry failed hold={}: {}", hold.getId(), e.getMessage());
            }
        }
        return changed;
    }

    private void queueAndSettle(MarketplaceEscrowHold hold, String trigger) {
        hold.setReleaseTrigger(trigger);
        hold.setStatus(MarketplaceEscrowHoldStatuses.RELEASE_QUEUED);
        hold.setReleasedAt(Instant.now());
        hold.setFailureReason(null);
        holdRepository.save(hold);
        settleNow(hold);
    }

    private void settleNow(MarketplaceEscrowHold hold) {
        PlatformKioskPaySettings settings = platformKioskPaySettings.requireEnabledSettings();
        if (settings.isSendMoneyFloatConstrained(Instant.now())) {
            hold.setStatus(MarketplaceEscrowHoldStatuses.FAILED);
            hold.setFailureReason("Platform Send Money float is temporarily constrained");
            holdRepository.save(hold);
            return;
        }

        Map<String, String> creds = platformKioskPaySettings.kopokopoCredentials()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Platform KopoKopo credentials are not configured"));

        Supplier supplier = supplierRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(hold.getSupplierId(), hold.getBusinessId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supplier missing for escrow"));

        if (!SupplierDisbursementService.hasAutomatedPayoutDestination(supplier)) {
            hold.setFailureReason("Supplier payout destination is missing or unverified");
            finalizeSendMoneyFailed(hold, hold.getFailureReason());
            return;
        }

        hold.setStatus(MarketplaceEscrowHoldStatuses.SETTLING);
        holdRepository.save(hold);

        String till = firstNonBlank(creds, "tillNumber", "shortcode");
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("escrowHoldId", hold.getId());
        metadata.put("businessId", hold.getBusinessId());
        metadata.put("supplierId", hold.getSupplierId());
        metadata.put("source", "marketplace_escrow");

        SendMoneyRequest request = buildSendMoneyRequest(
                supplier,
                creds,
                publicApiBaseUrl == null ? "" : publicApiBaseUrl.replaceAll("/+$", ""),
                hold.getAmount(),
                "Escrow " + hold.getId().substring(0, Math.min(8, hold.getId().length())),
                till,
                metadata);

        SendMoneyResult result = kopokopoPaymentGateway.sendMoney(request);
        if (result != null && !result.accepted() && "NETWORK_ERROR".equals(result.responseCode())) {
            // Ambiguous outcome: the request may have reached KopoKopo before the failure,
            // and Send Money is irreversible. Never refund on an unknown outcome — stay in
            // SETTLING without a send money id; the reconciler will not auto-refund it and
            // ops can resolve against the KopoKopo dashboard using the escrowHoldId metadata.
            hold.setFailureReason("KopoKopo unreachable during Send Money — outcome unknown, needs ops reconcile");
            holdRepository.save(hold);
            log.error("Escrow Send Money NETWORK_ERROR hold={} — held in SETTLING for manual reconcile", hold.getId());
            return;
        }
        if (result == null || !result.accepted() || result.sendMoneyId() == null || result.sendMoneyId().isBlank()) {
            String msg = result != null && result.message() != null
                    ? result.message()
                    : "KopoKopo Send Money declined";
            log.warn("Escrow Send Money declined hold={}: {}", hold.getId(), msg);
            finalizeSendMoneyFailed(hold, msg);
            return;
        }

        hold.setKopokopoSendMoneyId(result.sendMoneyId());
        if (hold.getSettleDisbursementId() == null) {
            hold.setSettleDisbursementId(UUID.randomUUID().toString());
        }
        hold.setStatus(MarketplaceEscrowHoldStatuses.SETTLING);
        holdRepository.save(hold);
        log.info("Marketplace escrow Send Money accepted: id={} sendMoneyId={}",
                hold.getId(), result.sendMoneyId());
    }

    private void finalizeSettled(MarketplaceEscrowHold hold) {
        if (MarketplaceEscrowHoldStatuses.SETTLED.equals(hold.getStatus())) {
            return;
        }
        KioskPayAccount account = walletService.getOrCreateForUpdate(hold.getBusinessId());
        walletService.settleEscrow(
                account,
                hold.getAmount(),
                hold.getCurrency(),
                hold.getId(),
                "escrow-settle:" + hold.getId());
        hold.setStatus(MarketplaceEscrowHoldStatuses.SETTLED);
        hold.setSettledAt(Instant.now());
        hold.setFailureReason(null);
        holdRepository.save(hold);
        log.info("Marketplace escrow settled: id={} sendMoneyId={}", hold.getId(), hold.getKopokopoSendMoneyId());
    }

    private void finalizeSendMoneyFailed(MarketplaceEscrowHold hold, String reason) {
        if (MarketplaceEscrowHoldStatuses.RELEASED_TO_SHOP.equals(hold.getStatus())
                || MarketplaceEscrowHoldStatuses.SETTLED.equals(hold.getStatus())
                || MarketplaceEscrowHoldStatuses.CANCELLED.equals(hold.getStatus())) {
            return;
        }
        hold.setFailureReason(reason);
        releaseBackToShop(hold, "escrow-send-fail:" + hold.getId());
        hold.setStatus(MarketplaceEscrowHoldStatuses.RELEASED_TO_SHOP);
        holdRepository.save(hold);
        log.warn("Marketplace escrow released to shop: id={} reason={}", hold.getId(), reason);
    }

    private void releaseBackToShop(MarketplaceEscrowHold hold, String reference) {
        KioskPayAccount account = walletService.getOrCreateForUpdate(hold.getBusinessId());
        walletService.releaseEscrowHold(
                account, hold.getAmount(), hold.getCurrency(), hold.getId(), reference);
        hold.setReleasedAt(Instant.now());
    }

    private MarketplaceEscrowHold requireHold(String businessId, String holdId) {
        return holdRepository.findByIdAndBusinessId(holdId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Escrow hold not found"));
    }

    private MarketplaceEscrowHoldResponse toResponse(MarketplaceEscrowHold h) {
        return new MarketplaceEscrowHoldResponse(
                h.getId(),
                h.getBusinessId(),
                h.getSupplierId(),
                h.getMarketplaceSupplierId(),
                h.getPurchaseOrderId(),
                h.getSupplierInvoiceId(),
                h.getAmount(),
                h.getCurrency(),
                h.getStatus(),
                h.getReleaseTrigger(),
                h.getFundedLedgerReference(),
                h.getKopokopoSendMoneyId(),
                h.getFailureReason(),
                h.getNote(),
                h.getCreatedAt(),
                h.getUpdatedAt(),
                h.getReleasedAt(),
                h.getSettledAt());
    }

    private static SendMoneyRequest buildSendMoneyRequest(
            Supplier supplier,
            Map<String, String> creds,
            String callbackBase,
            BigDecimal amount,
            String description,
            String sourceIdentifier,
            Map<String, String> metadata
    ) {
        String type = supplier.getPayoutType();
        if (SupplierPayoutTypes.MOBILE_WALLET.equals(type)) {
            return new SendMoneyRequest(
                    creds,
                    callbackBase,
                    SendMoneyRequest.DEST_MOBILE_WALLET,
                    supplier.getPayoutPhone(),
                    null,
                    null,
                    null,
                    amount,
                    "KES",
                    description,
                    sourceIdentifier,
                    metadata);
        }
        if (SupplierPayoutTypes.TILL.equals(type)) {
            return new SendMoneyRequest(
                    creds,
                    callbackBase,
                    SendMoneyRequest.DEST_TILL,
                    null,
                    supplier.getPayoutTillNumber(),
                    null,
                    null,
                    amount,
                    "KES",
                    description,
                    sourceIdentifier,
                    metadata);
        }
        if (SupplierPayoutTypes.PAYBILL.equals(type)) {
            return new SendMoneyRequest(
                    creds,
                    callbackBase,
                    SendMoneyRequest.DEST_PAYBILL,
                    null,
                    null,
                    supplier.getPayoutPaybillNumber(),
                    supplier.getPayoutPaybillAccount(),
                    amount,
                    "KES",
                    description,
                    sourceIdentifier,
                    metadata);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported payout type for escrow: " + type);
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

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
