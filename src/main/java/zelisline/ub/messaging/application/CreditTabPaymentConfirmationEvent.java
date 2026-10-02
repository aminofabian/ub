package zelisline.ub.messaging.application;

import java.math.BigDecimal;

/**
 * Published after a payment is applied to a customer's tab/credit balance, so the
 * customer gets a "we received your payment" message. Raised by the M-Pesa STK
 * settlement flow and by the staff-recorded / claim-approved flows.
 *
 * @param referenceId the settling record (STK intent id, claim id, ...) — for logs only
 * @param paymentMethod short human label such as {@link #METHOD_MPESA} or {@link #METHOD_CASH};
 *                      may be null when the rail is unknown
 */
public record CreditTabPaymentConfirmationEvent(
        String businessId,
        String referenceId,
        String customerId,
        BigDecimal amountPaid,
        BigDecimal balanceRemaining,
        String phoneDigits,
        String paymentMethod
) {
    public static final String METHOD_MPESA = "M-Pesa";
    public static final String METHOD_CASH = "cash";
}
