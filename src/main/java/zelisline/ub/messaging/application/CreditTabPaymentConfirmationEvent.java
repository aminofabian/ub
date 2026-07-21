package zelisline.ub.messaging.application;

import java.math.BigDecimal;

/**
 * Published after a public tab M-Pesa STK payment settles successfully.
 */
public record CreditTabPaymentConfirmationEvent(
        String businessId,
        String intentId,
        String customerId,
        BigDecimal amountPaid,
        BigDecimal balanceRemaining,
        String phoneDigits
) {
}
