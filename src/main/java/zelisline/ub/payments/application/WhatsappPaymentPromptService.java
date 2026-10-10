package zelisline.ub.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.StkPushContextType;

/**
 * {@link PaymentPromptPort} implementation for WhatsApp-initiated payment prompts.
 *
 * <p>Reuses the existing STK path exactly as the POS push does — {@link StkPushRetryHelper}
 * (MSISDN-clear + retry) then {@link GatewayStkPushService#registerPush} for reconciliation —
 * with {@link StkPushContextType#WHATSAPP_CHAT} and the {@code STOREFRONT} audience (a
 * customer-initiated payment: Daraja stays hidden until Super Admin approves the merchant).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsappPaymentPromptService implements PaymentPromptPort {

    private final StkPushRetryHelper stkPushRetryHelper;
    private final GatewayStkPushService gatewayStkPushService;

    @Override
    public PromptResult requestStk(String businessId, String phoneDigits, BigDecimal amount, String contextRef) {
        if (businessId == null || businessId.isBlank()) {
            return PromptResult.rejected("business_required");
        }
        if (amount == null || amount.signum() <= 0) {
            return PromptResult.rejected("amount_must_be_positive");
        }
        String phone = phoneDigits == null ? null : phoneDigits.replaceAll("\\D", "");
        if (phone == null || phone.isBlank()) {
            return PromptResult.rejected("phone_required");
        }

        // Match the KopoKopo/Safaricom whole-shilling charge used across the platform.
        BigDecimal charged = amount.setScale(0, RoundingMode.HALF_UP);
        String reference = "wa-" + (contextRef != null && !contextRef.isBlank()
                ? contextRef
                : UUID.randomUUID().toString());

        PaymentGatewayStkService.StkPushOutcome outcome = stkPushRetryHelper.initiateAfterClearingPhone(
                businessId, null, phone, charged, reference, "WhatsApp payment", StkAudience.STOREFRONT);

        if (outcome.accepted() && outcome.checkoutRequestId() != null) {
            gatewayStkPushService.registerPush(
                    businessId,
                    GatewayType.valueOf(outcome.gatewayType()),
                    outcome.configId(),
                    outcome.checkoutRequestId(),
                    reference,
                    StkPushContextType.WHATSAPP_CHAT,
                    contextRef,
                    charged,
                    phone);
            log.info("WhatsApp STK prompt initiated business={} amount={} checkout={}",
                    businessId, charged, outcome.checkoutRequestId());
            return PromptResult.accepted(reference, "Payment prompt sent to the customer.");
        }

        log.info("WhatsApp STK prompt not accepted business={} code={} message={}",
                businessId, outcome.responseCode(), outcome.message());
        return PromptResult.rejected(
                outcome.message() != null && !outcome.message().isBlank()
                        ? outcome.message()
                        : "stk_not_accepted");
    }
}
