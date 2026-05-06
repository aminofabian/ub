package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record SupplyPaymentHistoryRow(
        String supplierPaymentId,
        String allocationId,
        Instant paidAt,
        String paymentMethod,
        BigDecimal paymentCashAmount,
        BigDecimal amountAppliedToInvoice,
        String reference,
        String notes
) {
}
