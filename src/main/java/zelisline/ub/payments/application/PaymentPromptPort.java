package zelisline.ub.payments.application;

import java.math.BigDecimal;

/**
 * The seam the CRM uses to trigger an M-Pesa STK prompt.
 *
 * <p>Defined in {@code payments} and implemented by {@link WhatsappPaymentPromptService}; the CRM
 * depends only on this interface (never on {@code payments.infrastructure}). All money concerns —
 * gateway selection, idempotency, MSISDN retry, reconciliation, whole-shilling rounding, custody
 * rules — stay in {@code payments}. See {@code docs/scopes/whatsapp-crm/SCOPE.md} §6.7.
 */
public interface PaymentPromptPort {

    /**
     * Send an STK prompt to {@code phoneDigits} (MSISDN without {@code +}).
     *
     * @param contextRef conversation id, so the settlement event can be correlated back
     */
    PromptResult requestStk(String businessId, String phoneDigits, BigDecimal amount, String contextRef);

    record PromptResult(boolean accepted, String reference, String detail) {

        public static PromptResult accepted(String reference, String detail) {
            return new PromptResult(true, reference, detail);
        }

        public static PromptResult rejected(String detail) {
            return new PromptResult(false, null, detail);
        }
    }
}
