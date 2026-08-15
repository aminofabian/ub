package zelisline.ub.purchasing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.application.SupplierPayoutSettingsService;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.PaymentWebhookEvent;
import zelisline.ub.payments.domain.spi.SendMoneyRequest;
import zelisline.ub.payments.domain.spi.SendMoneyResult;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.infrastructure.KopokopoPaymentGateway;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;
import zelisline.ub.payments.repository.PaymentWebhookEventRepository;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.api.dto.SupplyKopokopoPayResponse;
import zelisline.ub.purchasing.api.dto.SupplyPayOptionsResponse;
import zelisline.ub.purchasing.domain.SupplierDisbursement;
import zelisline.ub.purchasing.domain.SupplierDisbursementStatuses;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.repository.SupplierDisbursementRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.purchasing.repository.SupplierPaymentAllocationRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierPayoutTypes;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class SupplierDisbursementService {

    private static final Logger log = LoggerFactory.getLogger(SupplierDisbursementService.class);
    private static final BigDecimal MONEY = new BigDecimal("0.01");

    private final SupplierDisbursementRepository disbursementRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierPaymentAllocationRepository allocationRepository;
    private final SupplierRepository supplierRepository;
    private final PaymentGatewayConfigRepository configRepository;
    private final SupplierPayoutSettingsService supplierPayoutSettingsService;
    private final CredentialEncryptionService encryptionService;
    private final KopokopoPaymentGateway kopokopoGateway;
    private final SupplierPaymentService supplierPaymentService;
    private final PaymentWebhookEventRepository webhookEventRepository;
    private final ObjectMapper objectMapper;
    private final PathBAssociatedCostService pathBAssociatedCostService;

    @Value("${app.public.api-base-url:http://localhost:5050}")
    private String publicApiBaseUrl;

    @Transactional(readOnly = true)
    public SupplyPayOptionsResponse payOptions(String businessId, String invoiceId) {
        SupplierInvoice inv = requirePayableInvoice(businessId, invoiceId);
        // Include soft-deleted suppliers so open supplies can still be paid or cleared.
        Supplier supplier = supplierRepository.findByIdAndBusinessId(inv.getSupplierId(), businessId)
                .orElse(null);

        BigDecimal open = openBalance(inv);
        Optional<PaymentGatewayConfig> payoutGateway = supplierPayoutSettingsService.resolveActivePayoutConfig(businessId);
        boolean supplierPayoutEnabled = supplierPayoutSettingsService.isSupplierPayoutToggleEnabled(businessId);
        boolean gatewayReady = payoutGateway.isPresent();
        boolean destinationConfigured = supplier != null
                && supplier.getDeletedAt() == null
                && hasAutomatedPayoutDestination(supplier);
        boolean kopokopoEligible = gatewayReady && destinationConfigured && open.compareTo(MONEY) > 0;

        Optional<SupplierDisbursement> pending = findPendingDisbursement(businessId, invoiceId);
        Optional<SupplierDisbursement> latest = disbursementRepository
                .findByBusinessIdAndSupplierInvoiceIdOrderByCreatedAtDesc(businessId, invoiceId)
                .stream()
                .findFirst();
        if (latest.isPresent() && isOpenForConfirm(latest.get())) {
            pollSendMoneyStatus(latest.get());
        }

        return new SupplyPayOptionsResponse(
                open,
                supplierPayoutEnabled,
                gatewayReady,
                payoutGateway.map(PaymentGatewayConfig::getLabel).orElse(null),
                destinationConfigured,
                destinationConfigured && supplier != null ? supplier.getPayoutType() : null,
                destinationConfigured && supplier != null ? supplier.getPayoutPhone() : null,
                destinationConfigured && supplier != null ? supplier.getPayoutTillNumber() : null,
                destinationConfigured && supplier != null ? supplier.getPayoutPaybillNumber() : null,
                destinationConfigured && supplier != null ? supplier.getPayoutPaybillAccount() : null,
                kopokopoEligible,
                pending.filter(d -> SupplierDisbursementStatuses.PENDING.equals(d.getStatus())).isPresent()
                        || latest.filter(d -> SupplierDisbursementStatuses.PENDING.equals(d.getStatus())).isPresent(),
                pending.map(SupplierDisbursement::getId)
                        .or(() -> latest.filter(d -> SupplierDisbursementStatuses.PENDING.equals(d.getStatus()))
                                .map(SupplierDisbursement::getId))
                        .orElse(null),
                latest.map(SupplierDisbursement::getStatus).orElse(null),
                latest.map(this::publicDisbursementMessage).orElse(null));
    }

    @Transactional
    public SupplyKopokopoPayResponse initiateKopokopoPay(String businessId, String invoiceId) {
        SupplierInvoice inv = requirePayableInvoice(businessId, invoiceId);
        BigDecimal open = openBalance(inv);
        if (open.compareTo(MONEY) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice has no open balance");
        }

        findPendingDisbursement(businessId, invoiceId).ifPresent(d -> {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A KopoKopo payment is already pending for this supply");
        });

        // Recover a timed-out row that actually transferred — avoid double Send Money.
        Optional<SupplierDisbursement> latest = disbursementRepository
                .findByBusinessIdAndSupplierInvoiceIdOrderByCreatedAtDesc(businessId, invoiceId)
                .stream()
                .findFirst();
        if (latest.isPresent() && isOpenForConfirm(latest.get())) {
            SupplierDisbursement prior = latest.get();
            pollSendMoneyStatus(prior);
            if (SupplierDisbursementStatuses.SUCCESS.equals(prior.getStatus())) {
                return new SupplyKopokopoPayResponse(
                        true,
                        prior.getId(),
                        prior.getKopokopoSendMoneyId(),
                        prior.getStatus(),
                        "Payment already confirmed with KopoKopo");
            }
            if (SupplierDisbursementStatuses.PENDING.equals(prior.getStatus())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A KopoKopo payment is already pending for this supply");
            }
        }
        Supplier supplier = supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(inv.getSupplierId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));

        if (!hasAutomatedPayoutDestination(supplier)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Supplier needs a KopoKopo payout destination (M-Pesa phone, till, or paybill)");
        }

        PaymentGatewayConfig cfg = supplierPayoutSettingsService.resolveActivePayoutConfig(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Supplier payouts are disabled or no active payout gateway is configured. "
                                + "Enable under Payments → Supplier payouts."));
        if (cfg.getGatewayType() != GatewayType.KOPOKOPO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Supplier payout via " + cfg.getGatewayType().name() + " is not implemented yet");
        }

        Map<String, String> creds = decryptCredentials(cfg);
        String till = creds.getOrDefault("tillNumber", creds.get("shortcode"));

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("supplierInvoiceId", inv.getId());
        metadata.put("supplierId", inv.getSupplierId());
        metadata.put("businessId", businessId);
        metadata.put("reference", inv.getInvoiceNumber());
        metadata.put("payoutType", supplier.getPayoutType());

        SendMoneyRequest request = buildSendMoneyRequest(
                supplier,
                creds,
                publicApiBaseUrl.replaceAll("/+$", ""),
                open,
                "Supply " + inv.getInvoiceNumber(),
                till,
                metadata);

        SendMoneyResult result = kopokopoGateway.sendMoney(request);
        if (!result.accepted() || result.sendMoneyId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    result.message() != null ? result.message() : "KopoKopo Send Money declined");
        }

        SupplierDisbursement row = new SupplierDisbursement();
        row.setBusinessId(businessId);
        row.setSupplierId(inv.getSupplierId());
        row.setSupplierInvoiceId(inv.getId());
        row.setGatewayType(GatewayType.KOPOKOPO);
        row.setPaymentGatewayConfigId(cfg.getId());
        row.setKopokopoSendMoneyId(result.sendMoneyId());
        row.setAmount(open);
        row.setCurrency("KES");
        row.setStatus(SupplierDisbursementStatuses.PENDING);
        try {
            row.setMetadataJson(objectMapper.writeValueAsString(metadata));
        } catch (Exception e) {
            log.warn("Could not serialize disbursement metadata", e);
        }
        disbursementRepository.save(row);

        log.info("Supplier disbursement pending: id={} invoice={} kopokopoId={} payoutType={}",
                row.getId(), invoiceId, result.sendMoneyId(), supplier.getPayoutType());

        return new SupplyKopokopoPayResponse(
                true,
                row.getId(),
                result.sendMoneyId(),
                SupplierDisbursementStatuses.PENDING,
                "Payment sent — waiting for KopoKopo confirmation");
    }

    @Transactional
    public SupplyKopokopoPayResponse disbursementStatus(String businessId, String invoiceId) {
        SupplierDisbursement d = disbursementRepository
                .findByBusinessIdAndSupplierInvoiceIdOrderByCreatedAtDesc(businessId, invoiceId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No disbursement found"));
        // Prefer live KopoKopo status over waiting for the callback (or timing out locally).
        if (isOpenForConfirm(d)) {
            pollSendMoneyStatus(d);
        }
        return toPayResponse(d);
    }

    /**
     * Stop waiting on a pending or failed Send Money that has not posted to the ledger.
     * Polls KopoKopo first so a completed transfer is recorded instead of cancelled.
     */
    @Transactional
    public SupplyKopokopoPayResponse cancelDisbursement(String businessId, String invoiceId) {
        SupplierDisbursement d = disbursementRepository
                .findByBusinessIdAndSupplierInvoiceIdOrderByCreatedAtDesc(businessId, invoiceId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No disbursement found"));

        if (SupplierDisbursementStatuses.SUCCESS.equals(d.getStatus())
                || d.getSupplierPaymentId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This payment already completed — it cannot be cancelled");
        }
        if (SupplierDisbursementStatuses.CANCELLED.equals(d.getStatus())) {
            return toPayResponse(d);
        }

        if (isOpenForConfirm(d)) {
            pollSendMoneyStatus(d);
        }
        if (SupplierDisbursementStatuses.SUCCESS.equals(d.getStatus())
                || d.getSupplierPaymentId() != null) {
            return toPayResponse(d);
        }

        markCancelled(d, "Cancelled — PalMart stopped waiting. If M-Pesa still completes, it will be recorded.");
        return toPayResponse(d);
    }

    @Transactional
    public boolean processKopokopoSendMoneyWebhook(
            String businessId,
            String configId,
            WebhookResult parsed
    ) {
        if (parsed == null || !"send_money".equalsIgnoreCase(parsed.topic())) {
            return false;
        }

        // KopoKopo reuses the same send_money resource id across Pending → Processed → Transferred.
        // Only terminal callbacks may consume that id for idempotency; intermediate ones must not.
        boolean terminal = parsed.success() || parsed.terminalFailure();
        String eventId = parsed.webhookEventId() != null && !parsed.webhookEventId().isBlank()
                ? parsed.webhookEventId()
                : parsed.gatewayCheckoutId();

        SupplierDisbursement disbursement = resolveDisbursement(businessId, parsed);
        if (disbursement == null) {
            log.warn("KopoKopo send_money: no matching disbursement business={} ref={} id={}",
                    businessId, parsed.reference(), parsed.gatewayCheckoutId());
            // Do not burn the event id — a later retry may arrive after the disbursement row exists.
            return true;
        }

        if (!isOpenForConfirm(disbursement)) {
            return true;
        }

        if (!terminal) {
            log.info("KopoKopo send_money intermediate webhook (not yet Transferred): eventId={} status pending for disbursement={}",
                    eventId, disbursement.getId());
            return true;
        }

        if (eventId != null && !eventId.isBlank()) {
            if (webhookEventRepository.existsByGatewayTypeAndGatewayEventId(GatewayType.KOPOKOPO, eventId)) {
                // Historical bug burned the id on intermediate callbacks; still settle on success.
                if (parsed.success()) {
                    log.info("KopoKopo send_money webhook id already seen; settling open disbursement on success: eventId={} disbursement={}",
                            eventId, disbursement.getId());
                    confirmDisbursement(disbursement, parsed);
                } else {
                    log.info("KopoKopo send_money webhook duplicate ignored: eventId={}", eventId);
                }
                return true;
            }
            try {
                PaymentWebhookEvent audit = new PaymentWebhookEvent();
                audit.setBusinessId(businessId);
                audit.setGatewayType(GatewayType.KOPOKOPO);
                audit.setGatewayEventId(eventId);
                audit.setTopic(parsed.topic());
                audit.setRawPayload(parsed.rawPayload());
                webhookEventRepository.save(audit);
            } catch (DataIntegrityViolationException e) {
                if (parsed.success() && isOpenForConfirm(disbursement)) {
                    log.info("KopoKopo send_money webhook duplicate on save; settling open disbursement: eventId={}",
                            eventId);
                    confirmDisbursement(disbursement, parsed);
                } else {
                    log.info("KopoKopo send_money webhook duplicate on save ignored: eventId={}", eventId);
                }
                return true;
            }
        }

        if (parsed.success()) {
            confirmDisbursement(disbursement, parsed);
            return true;
        }
        if (parsed.terminalFailure() && isOpenForConfirm(disbursement)
                && !SupplierDisbursementStatuses.FAILED.equals(disbursement.getStatus())) {
            markFailed(disbursement, kopokopoDeclineMessage(parsed));
            return true;
        }
        return true;
    }

    /**
     * Background recovery for autopay / missed webhooks: poll open Send Money rows against KopoKopo.
     */
    @Transactional
    public int pollOpenDisbursements(Instant createdAfter) {
        List<SupplierDisbursement> candidates = disbursementRepository
                .findByStatusInAndCreatedAtAfterOrderByCreatedAtAsc(
                        List.of(
                                SupplierDisbursementStatuses.PENDING,
                                SupplierDisbursementStatuses.FAILED,
                                SupplierDisbursementStatuses.CANCELLED),
                        createdAfter);
        int settled = 0;
        for (SupplierDisbursement d : candidates) {
            if (!isOpenForConfirm(d)) {
                continue;
            }
            String before = d.getStatus();
            pollSendMoneyStatus(d);
            if (SupplierDisbursementStatuses.SUCCESS.equals(d.getStatus())
                    || (SupplierDisbursementStatuses.FAILED.equals(d.getStatus())
                    && !before.equals(d.getStatus()))) {
                settled++;
            }
        }
        return settled;
    }

    /**
     * Asks KopoKopo for the current Send Money status and settles the disbursement when terminal.
     */
    private void pollSendMoneyStatus(SupplierDisbursement disbursement) {
        if (!isOpenForConfirm(disbursement)) {
            return;
        }
        String sendMoneyId = disbursement.getKopokopoSendMoneyId();
        if (sendMoneyId == null || sendMoneyId.isBlank()) {
            return;
        }
        String configId = disbursement.getPaymentGatewayConfigId();
        if (configId == null || configId.isBlank()) {
            return;
        }
        Optional<PaymentGatewayConfig> cfg = configRepository.findById(configId);
        if (cfg.isEmpty() || cfg.get().getGatewayType() != GatewayType.KOPOKOPO) {
            return;
        }
        try {
            Map<String, String> creds = decryptCredentials(cfg.get());
            WebhookResult status = kopokopoGateway.querySendMoneyStatus(sendMoneyId, creds);
            if (status == null || !"send_money".equalsIgnoreCase(status.topic())) {
                return;
            }
            if (status.success()) {
                confirmDisbursement(disbursement, status);
            } else if (status.terminalFailure()
                    && (SupplierDisbursementStatuses.PENDING.equals(disbursement.getStatus())
                    || SupplierDisbursementStatuses.CANCELLED.equals(disbursement.getStatus()))) {
                log.warn("KopoKopo Send Money declined: disbursement={} sendMoneyId={} reason={} payload={}",
                        disbursement.getId(),
                        sendMoneyId,
                        status.failureMessage(),
                        truncate(status.rawPayload(), 800));
                markFailed(disbursement, kopokopoDeclineMessage(status));
            }
        } catch (Exception e) {
            log.warn("KopoKopo Send Money poll failed for disbursement {}: {}",
                    disbursement.getId(), e.getMessage());
        }
    }

    /** Pending, cancelled, or timed-out/failed locally without a ledger payment yet. */
    private static boolean isOpenForConfirm(SupplierDisbursement disbursement) {
        if (disbursement.getSupplierPaymentId() != null) {
            return false;
        }
        String status = disbursement.getStatus();
        return SupplierDisbursementStatuses.PENDING.equals(status)
                || SupplierDisbursementStatuses.FAILED.equals(status)
                || SupplierDisbursementStatuses.CANCELLED.equals(status);
    }

    private SupplyKopokopoPayResponse toPayResponse(SupplierDisbursement d) {
        return new SupplyKopokopoPayResponse(
                SupplierDisbursementStatuses.SUCCESS.equals(d.getStatus()),
                d.getId(),
                d.getKopokopoSendMoneyId(),
                d.getStatus(),
                publicDisbursementMessage(d));
    }

    private String publicDisbursementMessage(SupplierDisbursement d) {
        if (d == null) {
            return null;
        }
        if (SupplierDisbursementStatuses.PENDING.equals(d.getStatus())) {
            return "Pending — waiting for KopoKopo / M-Pesa confirmation.";
        }
        if (SupplierDisbursementStatuses.CANCELLED.equals(d.getStatus())) {
            return d.getFailureReason() != null
                    ? d.getFailureReason()
                    : "Cancelled.";
        }
        if (d.getFailureReason() != null && !d.getFailureReason().isBlank()) {
            return d.getFailureReason();
        }
        return d.getStatus();
    }

    private static String kopokopoDeclineMessage(WebhookResult parsed) {
        String detail = parsed.failureMessage();
        if (detail != null && !detail.isBlank()
                && !"Failed".equalsIgnoreCase(detail)
                && !"Error".equalsIgnoreCase(detail)) {
            return "KopoKopo declined: " + detail.trim();
        }
        return "Payment declined by KopoKopo. Check till balance, Send Money permissions, and the payout destination in your KopoKopo dashboard.";
    }

    static boolean hasAutomatedPayoutDestination(Supplier supplier) {
        if (supplier == null || !SupplierPayoutTypes.isAutomated(supplier.getPayoutType())) {
            return false;
        }
        String type = supplier.getPayoutType();
        if (SupplierPayoutTypes.MOBILE_WALLET.equals(type)) {
            return supplier.getPayoutPhone() != null && !supplier.getPayoutPhone().isBlank();
        }
        if (SupplierPayoutTypes.TILL.equals(type)) {
            return supplier.getPayoutTillNumber() != null && !supplier.getPayoutTillNumber().isBlank();
        }
        if (SupplierPayoutTypes.PAYBILL.equals(type)) {
            return supplier.getPayoutPaybillNumber() != null && !supplier.getPayoutPaybillNumber().isBlank()
                    && supplier.getPayoutPaybillAccount() != null && !supplier.getPayoutPaybillAccount().isBlank();
        }
        return false;
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
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unsupported supplier payout type: " + type);
    }

    private static String truncate(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private void confirmDisbursement(SupplierDisbursement disbursement, WebhookResult parsed) {
        try {
            var paymentResponse = supplierPaymentService.recordKopokopoDisbursement(
                    disbursement.getBusinessId(),
                    disbursement.getSupplierId(),
                    disbursement.getSupplierInvoiceId(),
                    disbursement.getAmount(),
                    parsed.gatewayTransactionId() != null
                            ? parsed.gatewayTransactionId()
                            : disbursement.getKopokopoSendMoneyId(),
                    Instant.now());

            disbursement.setStatus(SupplierDisbursementStatuses.SUCCESS);
            disbursement.setSupplierPaymentId(paymentResponse.supplierPaymentId());
            disbursement.setConfirmedAt(Instant.now());
            disbursement.setFailureReason(null);
            disbursementRepository.save(disbursement);

            log.info("Supplier disbursement confirmed: id={} paymentId={}",
                    disbursement.getId(), paymentResponse.supplierPaymentId());
        } catch (Exception e) {
            log.error("Failed to post ledger for disbursement {}", disbursement.getId(), e);
            markFailed(disbursement, "Payment received but ledger post failed: " + e.getMessage());
        }
    }

    private void markCancelled(SupplierDisbursement disbursement, String reason) {
        disbursement.setStatus(SupplierDisbursementStatuses.CANCELLED);
        disbursement.setFailureReason(reason);
        disbursementRepository.save(disbursement);
        log.info("Supplier disbursement cancelled: id={} reason={}", disbursement.getId(), reason);
    }

    private void markFailed(SupplierDisbursement disbursement, String reason) {
        disbursement.setStatus(SupplierDisbursementStatuses.FAILED);
        disbursement.setFailureReason(reason);
        disbursementRepository.save(disbursement);
        log.warn("Supplier disbursement failed: id={} reason={}", disbursement.getId(), reason);
    }

    private SupplierDisbursement resolveDisbursement(String businessId, WebhookResult parsed) {
        if (parsed.gatewayCheckoutId() != null && !parsed.gatewayCheckoutId().isBlank()) {
            Optional<SupplierDisbursement> byKk = disbursementRepository.findByKopokopoSendMoneyId(
                    parsed.gatewayCheckoutId().trim());
            if (byKk.isPresent() && businessId.equals(byKk.get().getBusinessId())) {
                return byKk.get();
            }
        }
        if (parsed.reference() != null && !parsed.reference().isBlank()) {
            return disbursementRepository
                    .findFirstByBusinessIdAndSupplierInvoiceIdAndStatusOrderByCreatedAtDesc(
                            businessId,
                            parsed.reference().trim(),
                            SupplierDisbursementStatuses.PENDING)
                    .orElse(null);
        }
        return null;
    }

    private Optional<SupplierDisbursement> findPendingDisbursement(String businessId, String invoiceId) {
        Optional<SupplierDisbursement> pending = disbursementRepository
                .findFirstByBusinessIdAndSupplierInvoiceIdAndStatusOrderByCreatedAtDesc(
                        businessId, invoiceId, SupplierDisbursementStatuses.PENDING);
        pending.ifPresent(this::reconcileStalePending);
        if (pending.isPresent() && SupplierDisbursementStatuses.PENDING.equals(pending.get().getStatus())) {
            return pending;
        }
        return Optional.empty();
    }

    private void reconcileStalePending(SupplierDisbursement disbursement) {
        if (!SupplierDisbursementStatuses.PENDING.equals(disbursement.getStatus())) {
            return;
        }
        // Ask KopoKopo — stay pending while they still report pending. Do not invent Failed.
        pollSendMoneyStatus(disbursement);
    }

    private SupplierInvoice requirePayableInvoice(String businessId, String invoiceId) {
        SupplierInvoice inv = supplierInvoiceRepository.findByIdAndBusinessId(invoiceId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        if (!PurchasingConstants.INVOICE_POSTED.equals(inv.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice is not payable");
        }
        if (inv.getRawPurchaseSessionId() == null || inv.getRawPurchaseSessionId().isBlank()) {
            if (inv.getGoodsReceiptId() == null || inv.getGoodsReceiptId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a supply invoice");
            }
        }
        return inv;
    }

    private BigDecimal openBalance(SupplierInvoice inv) {
        BigDecimal paid = allocationRepository.sumAmountBySupplierInvoiceId(inv.getId());
        BigDecimal payable = pathBAssociatedCostService.payableGrandTotal(inv.getBusinessId(), inv);
        return payable.subtract(paid != null ? paid : BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private Map<String, String> decryptCredentials(PaymentGatewayConfig cfg) {
        try {
            String json = encryptionService.decrypt(cfg.getCredentialsJson());
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read gateway credentials");
        }
    }
}
