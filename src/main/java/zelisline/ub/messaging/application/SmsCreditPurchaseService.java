package zelisline.ub.messaging.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.messaging.api.dto.SmsCreditPurchaseDtos;
import zelisline.ub.messaging.domain.PlatformSmsCreditSettings;
import zelisline.ub.messaging.domain.SmsCreditLedgerKind;
import zelisline.ub.messaging.domain.SmsCreditPurchase;
import zelisline.ub.messaging.domain.SmsCreditPurchaseStatus;
import zelisline.ub.messaging.repository.SmsCreditPurchaseRepository;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.application.PaymentGatewayStkService;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.StkPushContextType;
import zelisline.ub.platform.application.PlatformDomainSettingsService;

/**
 * SMS credit top-ups settled through the platform M-Pesa STK till
 * (SMS_CREDITS_SCOPE.md §13). {@code status} + unique M-Pesa receipt guard the
 * STK callback against double-crediting.
 */
@Service
@RequiredArgsConstructor
public class SmsCreditPurchaseService {

    private static final Logger log = LoggerFactory.getLogger(SmsCreditPurchaseService.class);

    public static final String SMS_CREDITS_STK_CONFIG_ID = "platform-sms-credits-stk";

    private final SmsCreditPurchaseRepository purchaseRepository;
    private final SmsCreditSettingsService settingsService;
    private final SmsCreditService creditService;
    private final PaymentGatewayStkService paymentGatewayStkService;
    private final GatewayStkPushService gatewayStkPushService;
    private final PlatformDomainSettingsService platformDomainSettingsService;

    @Transactional
    public SmsCreditPurchaseDtos.SmsCreditPurchaseResponse initiate(
            String businessId,
            Integer credits,
            String rawPhone
    ) {
        PlatformSmsCreditSettings settings = settingsService.loadSingleton();
        if (!settings.isEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "SMS credit purchases are disabled by Super Admin.");
        }
        if (credits == null || credits <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter how many credits to buy");
        }
        if (credits < settings.getMinPurchaseCredits() || credits > settings.getMaxPurchaseCredits()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Credits must be between " + settings.getMinPurchaseCredits()
                            + " and " + settings.getMaxPurchaseCredits());
        }
        String phone = StkPhoneNormalizer.normalize(rawPhone);
        if (phone == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid M-Pesa phone number");
        }
        if (!platformDomainSettingsService.palmartStkConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Platform M-Pesa is not configured. Ask Super Admin to add Palmart STK under Platform → Domains.");
        }

        BigDecimal amount = settings.getUnitPriceKes()
                .multiply(BigDecimal.valueOf(credits))
                .setScale(2, RoundingMode.HALF_UP);

        SmsCreditPurchase purchase = new SmsCreditPurchase();
        purchase.setBusinessId(businessId);
        purchase.setCredits(credits);
        purchase.setAmountKes(amount);
        purchase.setPhoneNumber(phone);
        purchase.setStatus(SmsCreditPurchaseStatus.PENDING);
        SmsCreditPurchase saved = purchaseRepository.save(purchase);

        String reference = "smsc-" + saved.getId().replace("-", "").substring(0, 12)
                + "-" + UUID.randomUUID().toString().substring(0, 6);
        String description = credits + " SMS credits";

        gatewayStkPushService.cancelPendingForPhone(businessId, phone, "Replaced by SMS credits purchase");

        PaymentGatewayStkService.StkPushOutcome outcome = paymentGatewayStkService.initiateWithCredentials(
                GatewayType.KOPOKOPO.name(),
                SMS_CREDITS_STK_CONFIG_ID,
                businessId,
                platformDomainSettingsService.resolvePalmartStkCredentials(),
                phone,
                amount,
                reference,
                description);

        if (outcome.accepted() && outcome.checkoutRequestId() != null) {
            var push = gatewayStkPushService.registerPush(
                    businessId,
                    GatewayType.KOPOKOPO,
                    SMS_CREDITS_STK_CONFIG_ID,
                    outcome.checkoutRequestId(),
                    reference,
                    StkPushContextType.SMS_CREDIT_PURCHASE,
                    saved.getId(),
                    amount,
                    phone);
            saved.setStkPushId(push.getId());
            purchaseRepository.save(saved);
            return toResponse(saved, "Check your phone to complete M-Pesa payment.");
        }

        saved.setStatus(SmsCreditPurchaseStatus.FAILED);
        purchaseRepository.save(saved);
        return toResponse(saved,
                outcome.message() != null ? outcome.message() : "Payment request declined");
    }

    /**
     * STK callback for {@link StkPushContextType#SMS_CREDIT_PURCHASE} — credits the
     * tenant's purchased balance. Idempotent via purchase status + receipt guard.
     */
    @Transactional
    public void markPaid(String purchaseId, String checkoutId, String txnId) {
        SmsCreditPurchase purchase = purchaseRepository.findById(purchaseId).orElse(null);
        if (purchase == null) {
            log.warn("SMS credits purchase not found for STK settle purchaseId={}", purchaseId);
            return;
        }
        if (purchase.getStatus() == SmsCreditPurchaseStatus.PAID) {
            return;
        }
        if (purchase.getStatus() != SmsCreditPurchaseStatus.PENDING) {
            log.warn("SMS credits purchase not PENDING — ignoring STK settle purchaseId={} status={}",
                    purchaseId, purchase.getStatus());
            return;
        }
        if (txnId != null && !txnId.isBlank()) {
            purchaseRepository.findByMpesaReceipt(txnId.trim()).ifPresent(existing -> {
                if (existing.getStatus() == SmsCreditPurchaseStatus.PAID) {
                    throw new IllegalStateException(
                            "M-Pesa receipt already applied to another SMS credits purchase: " + txnId);
                }
            });
        }
        purchase.setStatus(SmsCreditPurchaseStatus.PAID);
        purchase.setMpesaReceipt(txnId != null ? txnId.trim() : null);
        purchase.setPaidAt(Instant.now());
        purchaseRepository.save(purchase);
        creditService.credit(
                purchase.getBusinessId(),
                purchase.getCredits(),
                SmsCreditLedgerKind.PURCHASE,
                "purchase:" + purchase.getId(),
                null);
    }

    /** STK push failed / expired — reopen the purchase for retry. */
    @Transactional
    public void markFailed(String businessId, String purchaseId, String reason) {
        SmsCreditPurchase purchase = purchaseRepository
                .findByIdAndBusinessId(purchaseId, businessId)
                .orElse(null);
        if (purchase == null || purchase.getStatus() != SmsCreditPurchaseStatus.PENDING) {
            return;
        }
        purchase.setStatus(SmsCreditPurchaseStatus.FAILED);
        purchaseRepository.save(purchase);
        log.info("SMS credits purchase failed business={} purchaseId={} reason={}",
                businessId, purchaseId, reason);
    }

    @Transactional(readOnly = true)
    public SmsCreditPurchaseDtos.SmsCreditPurchaseStatusResponse status(String businessId, String purchaseId) {
        SmsCreditPurchase purchase = purchaseRepository
                .findByIdAndBusinessId(purchaseId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase not found"));
        boolean needsRetry = purchase.getStatus() == SmsCreditPurchaseStatus.FAILED
                || purchase.getStatus() == SmsCreditPurchaseStatus.EXPIRED;
        return new SmsCreditPurchaseDtos.SmsCreditPurchaseStatusResponse(
                purchase.getId(),
                purchase.getStatus(),
                purchase.getAmountKes(),
                purchase.getMpesaReceipt(),
                purchase.getPaidAt(),
                needsRetry);
    }

    /** SA drill-down: recent purchases for a business. */
    @Transactional(readOnly = true)
    public java.util.List<SmsCreditPurchase> recentForBusiness(String businessId) {
        return purchaseRepository.findTop25ByBusinessIdOrderByCreatedAtDesc(businessId);
    }

    private static SmsCreditPurchaseDtos.SmsCreditPurchaseResponse toResponse(
            SmsCreditPurchase purchase,
            String message
    ) {
        return new SmsCreditPurchaseDtos.SmsCreditPurchaseResponse(
                purchase.getId(),
                purchase.getCredits(),
                purchase.getAmountKes(),
                purchase.getStatus(),
                purchase.getPhoneNumber(),
                message);
    }
}
