package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One tender line from {@code sale_payments}, joined to the parent sale —
 * used by the day payment ledger UI.
 */
public record PaymentLedgerRow(
        String paymentId,
        String saleId,
        Long receiptNo,
        Instant soldAt,
        String method,
        BigDecimal amount,
        String reference,
        int sortOrder,
        String status,
        String branchId,
        String cashierName,
        String customerName,
        BigDecimal saleGrandTotal
) {
}
