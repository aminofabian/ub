package zelisline.ub.opsalerts.application;

import java.math.BigDecimal;

/** Published after admin/claim credit payment (non-STK paths). */
public record CreditPaymentOpsAlertEvent(
        String businessId,
        String customerId,
        String customerName,
        BigDecimal amountPaid,
        BigDecimal balanceRemaining,
        String channel
) {
}
