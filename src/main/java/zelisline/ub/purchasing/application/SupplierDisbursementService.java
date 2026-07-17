package zelisline.ub.purchasing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.application.SupplierPayoutSettingsService;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
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

    @Value("${app.public.api-base-url:http://localhost:5050}")
    private String publicApiBaseUrl;

    @Value("${app.payments.send-money.stale-pending-seconds:180}")
    private int stalePendingSeconds;

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
        boolean mobilePayout = supplier != null
                && SupplierPayoutTypes.MOBILE_WALLET.equals(supplier.getPayoutType())
                && supplier.getPayoutPhone() != null
                && !supplier.getPayoutPhone().isBlank()
                && supplier.getDeletedAt() == null;
        boolean kopokopoEligible = gatewayReady && mobilePayout && open.compareTo(MONEY) > 0;

        Optional<SupplierDisbursement> pending = findPendingDisbursement(businessId, invoiceId);

        return new SupplyPayOptionsResponse(
                open,
                supplierPayoutEnabled,
                gatewayReady,
                payoutGateway.map(PaymentGatewayConfig::getLabel).orElse(null),
                mobilePayout,
                mobilePayout ? supplier.getPayoutPhone() : null,
                kopokopoEligible,
                pending.isPresent(),
                pending.map(SupplierDisbursement::getId).orElse(null));
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

        Supplier supplier = supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(inv.getSupplierId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));

        if (!SupplierPayoutTypes.MOBILE_WALLET.equals(supplier.getPayoutType())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Supplier payout type must be mobile_wallet for KopoKopo Send Money");
        }
        String phone = supplier.getPayoutPhone();
        if (phone == null || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supplier payout phone is required");
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

        SendMoneyRequest request = new SendMoneyRequest(
                creds,
                publicApiBaseUrl.replaceAll("/+$", ""),
                phone,
                open,
                "KES",
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

        log.info("Supplier disbursement pending: id={} invoice={} kopokopoId={}",
                row.getId(), invoiceId, result.sendMoneyId());

        return new SupplyKopokopoPayResponse(
                true,
                row.getId(),
                result.sendMoneyId(),
                SupplierDisbursementStatuses.PENDING,
                "M-Pesa payment sent — waiting for KopoKopo confirmation");
    }

    @Transactional
    public SupplyKopokopoPayResponse disbursementStatus(String businessId, String invoiceId) {
        SupplierDisbursement d = disbursementRepository
                .findByBusinessIdAndSupplierInvoiceIdOrderByCreatedAtDesc(businessId, invoiceId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No disbursement found"));
        reconcileStalePending(d);

        return new SupplyKopokopoPayResponse(
                SupplierDisbursementStatuses.SUCCESS.equals(d.getStatus()),
                d.getId(),
                d.getKopokopoSendMoneyId(),
                d.getStatus(),
                d.getFailureReason() != null ? d.getFailureReason() : d.getStatus());
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

        String eventId = parsed.webhookEventId() != null && !parsed.webhookEventId().isBlank()
                ? parsed.webhookEventId()
                : parsed.gatewayCheckoutId();
        if (eventId != null && !eventId.isBlank()) {
            if (webhookEventRepository.existsByGatewayTypeAndGatewayEventId(GatewayType.KOPOKOPO, eventId)) {
                log.info("KopoKopo send_money webhook duplicate ignored: eventId={}", eventId);
                return true;
            }
        }

        SupplierDisbursement disbursement = resolveDisbursement(businessId, parsed);
        if (disbursement == null) {
            log.warn("KopoKopo send_money: no matching disbursement business={} ref={} id={}",
                    businessId, parsed.reference(), parsed.gatewayCheckoutId());
            return true;
        }

        if (!SupplierDisbursementStatuses.PENDING.equals(disbursement.getStatus())) {
            return true;
        }

        if (parsed.success()) {
            confirmDisbursement(disbursement, parsed);
            return true;
        }
        if (parsed.terminalFailure()) {
            markFailed(disbursement, "Payment declined by KopoKopo");
            return true;
        }
        return true;
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
        Instant staleBefore = Instant.now().minus(Math.max(stalePendingSeconds, 60), ChronoUnit.SECONDS);
        if (disbursement.getCreatedAt() != null && disbursement.getCreatedAt().isBefore(staleBefore)) {
            markFailed(
                    disbursement,
                    "Timed out waiting for KopoKopo confirmation. Check KopoKopo or retry Send Money.");
        }
    }

    private SupplierInvoice requirePayableInvoice(String businessId, String invoiceId) {
        SupplierInvoice inv = supplierInvoiceRepository.findByIdAndBusinessId(invoiceId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        if (!PurchasingConstants.INVOICE_POSTED.equals(inv.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice is not payable");
        }
        if (inv.getRawPurchaseSessionId() == null || inv.getRawPurchaseSessionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a Path B supply invoice");
        }
        return inv;
    }

    private BigDecimal openBalance(SupplierInvoice inv) {
        BigDecimal paid = allocationRepository.sumAmountBySupplierInvoiceId(inv.getId());
        return inv.getGrandTotal().subtract(paid != null ? paid : BigDecimal.ZERO)
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
